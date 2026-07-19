package stock.batch.service.marketclose.biz;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationClockSnapshots;
import web.common.core.simulation.SimulationMarketSession;
import web.common.core.simulation.SimulationMarketSessions;

@Service
public class MarketSessionFenceService {

    private static final String DEFAULT_STATE_ID = "DEFAULT";
    private static final String DEFAULT_CLOCK_ID = "DEFAULT";
    private static final String ORDER_BOOK = "ORDER_BOOK";
    private static final String VIRTUAL_PRICE = "VIRTUAL_PRICE";
    private static final String OPEN = "OPEN";
    private static final String CLOSING = "CLOSING";
    private static final String CLOSED = "CLOSED";
    private static final String PREPARING = "PREPARING";

    private final JdbcClient jdbcClient;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final String sharedLockClause;
    private final String businessStateSharedLockClause;
    private final long staleAfterSeconds;
    private final Timer orderBookFenceAcquireTimer;

    public MarketSessionFenceService(
            JdbcTemplate jdbcTemplate,
            SimulationMarketSessionService simulationMarketSessionService,
            MeterRegistry meterRegistry,
            @Value("${stock.simulation-clock.stale-after-seconds:30}") long staleAfterSeconds
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationMarketSessionService = simulationMarketSessionService;
        boolean mySql = isMySql(jdbcTemplate);
        this.sharedLockClause = mySql ? "for share of f" : "for update";
        this.businessStateSharedLockClause = mySql ? "for share of b" : "for update";
        this.staleAfterSeconds = staleAfterSeconds;
        this.orderBookFenceAcquireTimer = Timer.builder("stock.orderbook.session.fence.duration")
                .description("Time spent validating and acquiring order-book session fences")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MarketSessionApproval> lockOpenOrderBookFences(Collection<String> symbols) {
        return orderBookFenceAcquireTimer.record(() -> lockOpenOrderBookFencesInternal(symbols));
    }

    /**
     * Protects low-frequency cash, account, holding, and corporate-action mutations while the
     * full-market ledger snapshot is built in bounded chunks. Order and execution paths never call
     * this method. A mutation chunk that starts first holds the singleton business-state row with
     * a shared lock, so close waits and includes it; once CLOSE_REQUESTED is visible, later chunks
     * fail before account, holding, entitlement, or instrument locks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireMarketLedgerMutationPermit(String operation) {
        LocalDate activeBusinessDate = lockActiveBusinessDate();
        LedgerMutationBlockers blockers = jdbcClient.sql(
                        """
                        select exists (
                                   select 1
                                     from stock_post_close_cycle
                                    where business_date = ?
                                      and scope_type = 'FULL_MARKET'
                                      and scope_key = 'ALL'
                                      and phase in (
                                          'OPEN', 'CLOSE_REQUESTED',
                                          'ORDER_ENTRY_CLOSED', 'EXECUTION_DRAINED'
                                      )
                               ) as freeze_pending,
                               exists (
                                   select 1
                                     from stock_order_book_market_config
                                    where enabled = true
                                      and market_status = 'OPEN'
                               ) or exists (
                                   select 1
                                     from stock_virtual_market_config
                                    where enabled = true
                                      and market_status = 'OPEN'
                               ) or exists (
                                   select 1
                                     from stock_market_session_fence fence
                                     join stock_order_book_market_config config
                                       on config.symbol = fence.symbol
                                      and config.enabled = true
                                    where fence.market_type = 'ORDER_BOOK'
                                      and fence.session_state = 'OPEN'
                               ) or exists (
                                   select 1
                                     from stock_market_session_fence fence
                                     join stock_virtual_market_config config
                                       on config.symbol = fence.symbol
                                      and config.enabled = true
                                    where fence.market_type = 'VIRTUAL_PRICE'
                                      and fence.session_state = 'OPEN'
                               ) as market_open
                        """
                )
                .param(activeBusinessDate)
                .query((rs, rowNum) -> new LedgerMutationBlockers(
                        rs.getBoolean("freeze_pending"),
                        rs.getBoolean("market_open")
                ))
                .single();
        if (blockers.marketOpen()) {
            throw new IllegalStateException(
                    "Cannot run " + normalizeOperation(operation) + " while any enabled market is open"
            );
        }
        if (blockers.freezePending()) {
            throw new IllegalStateException(
                    "Market close ledger freeze is in progress; retry after LEDGER_FROZEN: "
                            + normalizeOperation(operation)
            );
        }
    }

    /**
     * Protects the low-frequency compatibility market-data writer from crossing the close/freeze
     * boundary. Unlike {@link #acquireMarketLedgerMutationPermit(String)}, this permit deliberately
     * allows a regular-session price update. It holds only the singleton business-state row with a
     * shared lock; order and execution transactions lock symbol fences instead, so this does not
     * serialize live order traffic. A close that starts first is observed through the cycle phase,
     * while a refresh that starts first makes beginClose wait until its price write commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireLiveMarketDataMutationPermit(String operation) {
        LocalDate activeBusinessDate = lockActiveBusinessDate();
        Boolean freezePending = jdbcClient.sql(
                        """
                        select exists (
                            select 1
                              from stock_post_close_cycle
                             where business_date = ?
                               and scope_type = 'FULL_MARKET'
                               and scope_key = 'ALL'
                               and phase in (
                                   'OPEN', 'CLOSE_REQUESTED',
                                   'ORDER_ENTRY_CLOSED', 'EXECUTION_DRAINED'
                               )
                        )
                        """
                )
                .param(activeBusinessDate)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(freezePending)) {
            throw new IllegalStateException(
                    "Market close ledger freeze is in progress; retry after LEDGER_FROZEN: "
                            + normalizeOperation(operation)
            );
        }
    }

    /**
     * Fails before a low-frequency stage reads its potentially large target cohort. The per-chunk
     * mandatory permit remains required because close or open can race after this preflight ends.
     */
    @Transactional
    public void assertMarketLedgerMutationAllowed(String operation) {
        acquireMarketLedgerMutationPermit(operation);
    }

    private LocalDate lockActiveBusinessDate() {
        return jdbcClient.sql(
                        """
                        select b.active_business_date
                          from stock_market_business_state b
                         where b.state_id = ?
                        %s
                        """.formatted(businessStateSharedLockClause)
                )
                .param(DEFAULT_STATE_ID)
                .query(LocalDate.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Active market business date is not initialized"
                ));
    }

    private Optional<MarketSessionApproval> lockOpenOrderBookFencesInternal(Collection<String> symbols) {
        List<String> normalizedSymbols = symbols == null
                ? List.of()
                : symbols.stream()
                        .filter(symbol -> symbol != null && !symbol.isBlank())
                        .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                        .distinct()
                        .sorted()
                        .toList();
        if (normalizedSymbols.isEmpty()) {
            return Optional.empty();
        }

        List<MarketSessionGateRow> gates = lockOpenOrderBookFencesWithContext(normalizedSymbols);
        if (gates.size() != normalizedSymbols.size()) {
            return Optional.empty();
        }
        for (int index = 0; index < normalizedSymbols.size(); index++) {
            if (!normalizedSymbols.get(index).equals(gates.get(index).fence().symbol())) {
                return Optional.empty();
            }
        }
        MarketSessionGateRow gate = gates.getFirst();
        List<MarketSessionFenceRow> fences = gates.stream().map(MarketSessionGateRow::fence).toList();

        SimulationClockSnapshot clock = toClockSnapshot(gate);
        SimulationMarketSession session = SimulationMarketSessions.resolve(
                clock.simulationDateTime(),
                simulationMarketSessionService.openTime(),
                simulationMarketSessionService.closeTime()
        );
        if (session != SimulationMarketSession.REGULAR
                || !gate.activeBusinessDate().equals(clock.simulationDate())
                || !gate.activeBusinessDate().equals(gate.rawSimulationDate())
                || gate.preparingBusinessDate() != null
                || fences.stream().anyMatch(fence -> !OPEN.equals(fence.sessionState())
                        || !gate.activeBusinessDate().equals(fence.businessDate()))) {
            return Optional.empty();
        }
        Map<String, Long> sessionEpochs = fences.stream().collect(Collectors.toUnmodifiableMap(
                MarketSessionFenceRow::symbol,
                MarketSessionFenceRow::sessionEpoch
        ));
        return Optional.of(new MarketSessionApproval(
                gate.activeBusinessDate(),
                sessionEpochs,
                clock.simulationDateTime()
        ));
    }

    @Transactional
    public int openRegularSession(LocalDate rawSimulationDate, LocalDateTime changedAt) {
        ensureBusinessState(rawSimulationDate, changedAt);
        int changedCount = transitionMarketConfigs(rawSimulationDate, changedAt);
        updateBusinessStateForOpen(rawSimulationDate, changedAt);
        return changedCount;
    }

    @Transactional
    public int keepClosedForPreOpen(LocalDate rawSimulationDate, LocalDateTime changedAt) {
        MarketBusinessState state = ensureBusinessState(rawSimulationDate, changedAt);
        int changedCount = closeOpenMarketConfigs(changedAt);
        transitionFencesForPreOpen(state.activeBusinessDate().plusDays(1), changedAt);
        updatePreparingBusinessDate(state.activeBusinessDate(), rawSimulationDate, changedAt);
        return changedCount;
    }

    @Transactional
    public int beginClose(LocalDate requestedBusinessDate, LocalDateTime changedAt, String symbol) {
        return beginClose(
                requestedBusinessDate,
                simulationMarketSessionService.currentSimulationDate(),
                changedAt,
                symbol
        );
    }

    @Transactional
    public int beginClose(
            LocalDate requestedBusinessDate,
            LocalDate rawSimulationDate,
            LocalDateTime changedAt,
            String symbol
    ) {
        if (requestedBusinessDate == null || rawSimulationDate == null || changedAt == null) {
            throw new IllegalArgumentException("Market close requires business, raw, and change dates");
        }
        MarketBusinessState state = ensureBusinessState(requestedBusinessDate, changedAt);
        if (!state.activeBusinessDate().equals(requestedBusinessDate)) {
            throw new IllegalStateException(
                    "Market close business date does not match active business date: requested=%s, active=%s"
                            .formatted(requestedBusinessDate, state.activeBusinessDate())
            );
        }
        if (rawSimulationDate.isBefore(state.activeBusinessDate())) {
            throw new IllegalStateException(
                    "Raw simulation date cannot precede active business date: raw=%s, active=%s"
                            .formatted(rawSimulationDate, state.activeBusinessDate())
            );
        }
        String normalizedSymbol = normalizeNullableSymbol(symbol);
        int changedCount = transitionOrderBookToClosing(
                state.activeBusinessDate(),
                changedAt,
                normalizedSymbol
        );
        if (normalizedSymbol == null) {
            changedCount += transitionVirtualMarketsToClosed(state.activeBusinessDate(), changedAt);
        }
        // A recovered close can target an active business date older than the raw clock date.
        // Never move the raw simulation date backwards to the close target or a stale caller.
        LocalDate monotonicRawDate = rawSimulationDate.isBefore(state.rawSimulationDate())
                ? state.rawSimulationDate()
                : rawSimulationDate;
        updateRawSimulationDate(monotonicRawDate, changedAt);
        return changedCount;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeOrderBookClose(String symbol, LocalDate businessDate, LocalDateTime changedAt) {
        String normalizedSymbol = normalizeNullableSymbol(symbol);
        String symbolPredicate = normalizedSymbol == null ? "" : "and symbol = :symbol";
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                        """
                        update stock_market_session_fence
                           set session_state = 'CLOSED',
                               state_changed_at = :changedAt,
                               version = version + 1,
                               updated_at = :changedAt
                         where market_type = 'ORDER_BOOK'
                           and business_date = :businessDate
                           and session_state in ('CLOSING', 'CLOSED')
                           %s
                        """.formatted(symbolPredicate)
                )
                .param("changedAt", changedAt)
                .param("businessDate", businessDate);
        if (normalizedSymbol != null) {
            statement = statement.param("symbol", normalizedSymbol);
        }
        statement.update();
    }

    public LocalDate activeBusinessDate() {
        MarketBusinessState state = findBusinessState();
        return state == null ? null : state.activeBusinessDate();
    }

    public LocalDate preparingBusinessDate() {
        MarketBusinessState state = findBusinessState();
        return state == null ? null : state.preparingBusinessDate();
    }

    public MarketBusinessStateSnapshot businessState() {
        MarketBusinessState state = findBusinessState();
        if (state == null) {
            return null;
        }
        return new MarketBusinessStateSnapshot(
                state.activeBusinessDate(),
                state.preparingBusinessDate(),
                state.rawSimulationDate(),
                state.version()
        );
    }

    public boolean isRegularSessionOpen(LocalDate businessDate) {
        if (businessDate == null) {
            return false;
        }
        Long invalidCount = jdbcClient.sql(
                        """
                        select (
                            select case when exists (
                                select 1
                                  from stock_market_business_state
                                 where state_id = ?
                                   and active_business_date = ?
                                   and raw_simulation_date = ?
                                   and preparing_business_date is null
                            ) then 0 else 1 end
                        ) + (
                            select count(*)
                              from stock_order_book_market_config config
                             where config.enabled = true
                               and (
                                   config.market_status <> 'OPEN'
                                   or not exists (
                                       select 1
                                         from stock_market_session_fence fence
                                        where fence.market_type = 'ORDER_BOOK'
                                          and fence.symbol = config.symbol
                                          and fence.business_date = ?
                                          and fence.session_state = 'OPEN'
                                   )
                               )
                        )
                        """
                )
                .param(DEFAULT_STATE_ID)
                .param(businessDate)
                .param(businessDate)
                .param(businessDate)
                .query(Long.class)
                .single();
        return invalidCount != null && invalidCount == 0L;
    }

    /**
     * Read-only fast path for the periodic session synchronizer. Once the regular-session state
     * is already aligned, the scheduler must not take an exclusive lock on every symbol fence
     * every poll because live orders and executions hold those rows with a shared lock.
     */
    public boolean isRegularSessionSynchronized(LocalDate businessDate) {
        if (businessDate == null) {
            return false;
        }
        MarketBusinessState state = findBusinessState();
        if (state == null
                || !businessDate.equals(state.activeBusinessDate())
                || !businessDate.equals(state.rawSimulationDate())
                || state.preparingBusinessDate() != null) {
            return false;
        }
        LocalDateTime openBoundary = businessDate.atTime(simulationMarketSessionService.openTime());
        List<MarketConfigFenceStateRow> rows = jdbcClient.sql(
                        """
                        select config.market_type,
                               config.symbol,
                               config.enabled,
                               config.market_status,
                               config.updated_at,
                               fence.business_date,
                               fence.session_state
                          from (
                               select 'ORDER_BOOK' as market_type,
                                      symbol, enabled, market_status, updated_at
                                 from stock_order_book_market_config
                               union all
                               select 'VIRTUAL_PRICE' as market_type,
                                      symbol, enabled, market_status, updated_at
                                 from stock_virtual_market_config
                          ) config
                          left join stock_market_session_fence fence
                            on fence.market_type = config.market_type
                           and fence.symbol = config.symbol
                         order by config.market_type, config.symbol
                        """
                )
                .query((rs, rowNum) -> new MarketConfigFenceStateRow(
                        rs.getBoolean("enabled"),
                        rs.getString("market_status"),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("session_state")
                ))
                .list();
        return rows.stream().allMatch(row -> regularStateMatches(row, businessDate, openBoundary));
    }

    public boolean isAfterCloseSynchronized(LocalDate businessDate, LocalDate rawSimulationDate) {
        MarketBusinessState state = findBusinessState();
        return state != null
                && businessDate != null
                && rawSimulationDate != null
                && businessDate.equals(state.activeBusinessDate())
                && rawSimulationDate.equals(state.rawSimulationDate())
                && !hasOpenMarket();
    }

    public boolean isPreOpenSynchronized(LocalDate rawSimulationDate) {
        MarketBusinessState state = findBusinessState();
        return state != null
                && rawSimulationDate != null
                && rawSimulationDate.equals(state.rawSimulationDate())
                && state.activeBusinessDate().plusDays(1).equals(state.preparingBusinessDate())
                && !hasOpenMarket()
                && arePreOpenFencesSynchronized(state.preparingBusinessDate());
    }

    public boolean hasOpenMarket() {
        Boolean open = jdbcClient.sql(
                        """
                        select exists (
                            select 1
                              from stock_order_book_market_config
                             where enabled = true
                               and market_status = 'OPEN'
                        ) or exists (
                            select 1
                              from stock_virtual_market_config
                             where enabled = true
                               and market_status = 'OPEN'
                        ) or exists (
                            select 1
                              from stock_market_session_fence fence
                              join stock_order_book_market_config config
                                on config.symbol = fence.symbol
                               and config.enabled = true
                             where fence.market_type = 'ORDER_BOOK'
                               and fence.session_state = 'OPEN'
                        ) or exists (
                            select 1
                              from stock_market_session_fence fence
                              join stock_virtual_market_config config
                                on config.symbol = fence.symbol
                               and config.enabled = true
                             where fence.market_type = 'VIRTUAL_PRICE'
                               and fence.session_state = 'OPEN'
                        )
                        """
                )
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(open);
    }

    /**
     * Cheap admission gate for regular-session workers. It reads only the small configuration,
     * fence, and singleton business-state tables; the strict shared fence lock is still acquired
     * inside each order or execution transaction.
     */
    public boolean hasOpenOrderBookMarket() {
        Boolean open = jdbcClient.sql(
                        """
                        select exists (
                            select 1
                              from stock_order_book_market_config config
                              join stock_market_session_fence fence
                                on fence.market_type = 'ORDER_BOOK'
                               and fence.symbol = config.symbol
                              join stock_market_business_state business_state
                                on business_state.state_id = ?
                               and business_state.active_business_date = fence.business_date
                             where config.enabled = true
                               and config.market_status = 'OPEN'
                               and fence.session_state = 'OPEN'
                             limit 1
                        )
                        """
                )
                .param(DEFAULT_STATE_ID)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(open);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceClosedBusinessDate(
            LocalDate expectedActiveBusinessDate,
            LocalDate nextBusinessDate,
            LocalDate rawSimulationDate,
            LocalDateTime changedAt
    ) {
        if (expectedActiveBusinessDate == null
                || nextBusinessDate == null
                || rawSimulationDate == null
                || changedAt == null) {
            throw new IllegalArgumentException("Closed business-date advance requires all date values");
        }
        MarketBusinessState state = ensureBusinessState(rawSimulationDate, changedAt);
        if (state.activeBusinessDate().equals(nextBusinessDate)) {
            return;
        }
        if (!state.activeBusinessDate().equals(expectedActiveBusinessDate)
                || !expectedActiveBusinessDate.plusDays(1).equals(nextBusinessDate)) {
            throw new IllegalStateException(
                    "Closed business-date advance is not sequential: expected=%s, actual=%s, next=%s"
                            .formatted(expectedActiveBusinessDate, state.activeBusinessDate(), nextBusinessDate)
            );
        }
        if (hasOpenMarket()) {
            throw new IllegalStateException("Cannot skip a business date while any market is open");
        }

        jdbcClient.sql(
                        """
                        update stock_market_session_fence
                           set business_date = ?,
                               session_epoch = session_epoch + 1,
                               session_state = 'CLOSED',
                               state_changed_at = ?,
                               version = version + 1,
                               updated_at = ?
                        """
                )
                .param(nextBusinessDate)
                .param(changedAt)
                .param(changedAt)
                .update();
        int updated = jdbcClient.sql(
                        """
                        update stock_market_business_state
                           set active_business_date = ?,
                               preparing_business_date = ?,
                               raw_simulation_date = ?,
                               version = version + 1,
                               updated_at = ?
                         where state_id = ?
                           and active_business_date = ?
                        """
                )
                .param(nextBusinessDate)
                .param(nextBusinessDate.plusDays(1))
                .param(rawSimulationDate)
                .param(changedAt)
                .param(DEFAULT_STATE_ID)
                .param(expectedActiveBusinessDate)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Market business state changed during skipped-date recovery");
        }
    }

    private int transitionMarketConfigs(LocalDate targetBusinessDate, LocalDateTime changedAt) {
        List<MarketConfigRow> configs = findAllMarketConfigs();
        int changedCount = 0;
        LocalDateTime openBoundary = targetBusinessDate.atTime(simulationMarketSessionService.openTime());
        for (MarketConfigRow config : configs) {
            boolean statusAllowsOpen = OPEN.equals(config.marketStatus())
                    || ((CLOSED.equals(config.marketStatus()) || "CIRCUIT_BREAKER".equals(config.marketStatus()))
                    && config.updatedAt().isBefore(openBoundary));
            boolean shouldOpen = config.enabled() && statusAllowsOpen;
            String targetState = shouldOpen ? OPEN : CLOSED;
            transitionFence(config.marketType(), config.symbol(), targetBusinessDate, targetState, changedAt);
            if (shouldOpen) {
                changedCount += updateMarketConfigStatus(config, OPEN, changedAt);
            }
        }
        return changedCount;
    }

    private void transitionFencesForPreOpen(LocalDate preparingBusinessDate, LocalDateTime changedAt) {
        for (MarketConfigRow config : findAllMarketConfigs()) {
            String targetState = config.enabled() ? PREPARING : CLOSED;
            transitionFence(
                    config.marketType(),
                    config.symbol(),
                    preparingBusinessDate,
                    targetState,
                    changedAt
            );
        }
    }

    /**
     * Uses only the bounded market configuration and fence rows. This fast path prevents the
     * session synchronizer from taking an exclusive fence lock every poll during PRE_OPEN while
     * still proving that every enabled symbol is fenced for the date being prepared.
     */
    private boolean arePreOpenFencesSynchronized(LocalDate preparingBusinessDate) {
        Boolean synchronizedState = jdbcClient.sql(
                        """
                        select not exists (
                            select 1
                              from (
                                   select 'ORDER_BOOK' as market_type, symbol, enabled
                                     from stock_order_book_market_config
                                   union all
                                   select 'VIRTUAL_PRICE' as market_type, symbol, enabled
                                     from stock_virtual_market_config
                              ) config
                              left join stock_market_session_fence fence
                                on fence.market_type = config.market_type
                               and fence.symbol = config.symbol
                             where fence.symbol is null
                                or fence.business_date <> ?
                                or fence.session_state <> case
                                       when config.enabled = true then 'PREPARING'
                                       else 'CLOSED'
                                   end
                        )
                        """
                )
                .param(preparingBusinessDate)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(synchronizedState);
    }

    private int transitionOrderBookToClosing(LocalDate businessDate, LocalDateTime changedAt, String symbol) {
        return findMarketConfigs(ORDER_BOOK, symbol).stream()
                .sorted(Comparator.comparing(MarketConfigRow::symbol))
                .mapToInt(config -> {
                    boolean shouldDrain = config.enabled() && OPEN.equals(config.marketStatus());
                    String targetState = shouldDrain ? CLOSING : CLOSED;
                    if (CLOSING.equals(targetState)) {
                        transitionFenceToClosing(config.symbol(), businessDate, changedAt);
                    } else {
                        transitionFence(ORDER_BOOK, config.symbol(), businessDate, targetState, changedAt);
                    }
                    return shouldDrain ? updateMarketConfigStatus(config, CLOSED, changedAt) : 0;
                })
                .sum();
    }

    private int transitionFenceToClosing(String symbol, LocalDate businessDate, LocalDateTime changedAt) {
        MarketSessionFenceRow current = lockFenceForUpdate(ORDER_BOOK, symbol);
        if (current == null) {
            insertFence(ORDER_BOOK, symbol, businessDate, CLOSING, changedAt);
            return 1;
        }
        if (businessDate.equals(current.businessDate())
                && (CLOSING.equals(current.sessionState()) || CLOSED.equals(current.sessionState()))) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        update stock_market_session_fence
                           set business_date = ?,
                               session_epoch = session_epoch + 1,
                               session_state = 'CLOSING',
                               state_changed_at = ?,
                               version = version + 1,
                               updated_at = ?
                         where market_type = 'ORDER_BOOK'
                           and symbol = ?
                        """
                )
                .param(businessDate)
                .param(changedAt)
                .param(changedAt)
                .param(symbol)
                .update();
    }

    private int transitionVirtualMarketsToClosed(LocalDate businessDate, LocalDateTime changedAt) {
        return findMarketConfigs(VIRTUAL_PRICE, null).stream()
                .sorted(Comparator.comparing(MarketConfigRow::symbol))
                .mapToInt(config -> {
                    transitionFence(VIRTUAL_PRICE, config.symbol(), businessDate, CLOSED, changedAt);
                    return config.enabled() && OPEN.equals(config.marketStatus())
                            ? updateMarketConfigStatus(config, CLOSED, changedAt)
                            : 0;
                })
                .sum();
    }

    private int transitionFence(
            String marketType,
            String symbol,
            LocalDate businessDate,
            String targetState,
            LocalDateTime changedAt
    ) {
        MarketSessionFenceRow current = lockFenceForUpdate(marketType, symbol);
        if (current == null) {
            insertFence(marketType, symbol, businessDate, targetState, changedAt);
            return 1;
        }
        if (businessDate.equals(current.businessDate()) && targetState.equals(current.sessionState())) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        update stock_market_session_fence
                           set business_date = ?,
                               session_epoch = session_epoch + 1,
                               session_state = ?,
                               state_changed_at = ?,
                               version = version + 1,
                               updated_at = ?
                         where market_type = ?
                           and symbol = ?
                        """
                )
                .param(businessDate)
                .param(targetState)
                .param(changedAt)
                .param(changedAt)
                .param(marketType)
                .param(symbol)
                .update();
    }

    private void insertFence(
            String marketType,
            String symbol,
            LocalDate businessDate,
            String targetState,
            LocalDateTime changedAt
    ) {
        try {
            jdbcClient.sql(
                            """
                            insert into stock_market_session_fence(
                                market_type,
                                symbol,
                                business_date,
                                session_epoch,
                                session_state,
                                state_changed_at,
                                version,
                                created_at,
                                updated_at
                            )
                            values (?, ?, ?, 1, ?, ?, 0, ?, ?)
                            """
                    )
                    .param(marketType)
                    .param(symbol)
                    .param(businessDate)
                    .param(targetState)
                    .param(changedAt)
                    .param(changedAt)
                    .param(changedAt)
                    .update();
        } catch (DuplicateKeyException ex) {
            transitionFence(marketType, symbol, businessDate, targetState, changedAt);
        }
    }

    /**
     * Locks every requested symbol in one ordered SQL statement. Auto-market chunks can contain
     * several symbols, so a single round trip avoids one fence query per planned symbol while the
     * primary-key range remains bounded by the small chunk symbol set.
     */
    private List<MarketSessionGateRow> lockOpenOrderBookFencesWithContext(List<String> symbols) {
        return jdbcClient.sql(
                        """
                        select f.symbol,
                               f.business_date,
                               f.session_epoch,
                               f.session_state,
                               b.active_business_date,
                               b.preparing_business_date,
                               b.raw_simulation_date,
                               sc.base_simulation_date,
                               sc.real_seconds_per_simulation_day,
                               sc.accumulated_real_seconds,
                               sc.running,
                               sc.last_started_at,
                               sc.last_heartbeat_at
                          from stock_market_session_fence f
                          join stock_order_book_market_config c
                            on c.symbol = f.symbol
                           and c.enabled = true
                           and c.market_status = 'OPEN'
                          join stock_market_business_state b
                            on b.state_id = '%s'
                         join stock_simulation_clock sc
                            on sc.clock_id = '%s'
                         where f.market_type = 'ORDER_BOOK'
                           and f.symbol in (:symbols)
                         order by f.symbol
                        %s
                        """.formatted(DEFAULT_STATE_ID, DEFAULT_CLOCK_ID, sharedLockClause)
                )
                .param("symbols", symbols)
                .query((rs, rowNum) -> new MarketSessionGateRow(
                        mapFence(rs),
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class),
                        rs.getObject("raw_simulation_date", LocalDate.class),
                        rs.getObject("base_simulation_date", LocalDate.class),
                        rs.getInt("real_seconds_per_simulation_day"),
                        rs.getLong("accumulated_real_seconds"),
                        rs.getBoolean("running"),
                        rs.getObject("last_started_at", LocalDateTime.class),
                        rs.getObject("last_heartbeat_at", LocalDateTime.class)
                ))
                .list();
    }

    private SimulationClockSnapshot toClockSnapshot(MarketSessionGateRow gate) {
        return SimulationClockSnapshots.calculate(
                gate.baseSimulationDate(),
                gate.realSecondsPerSimulationDay(),
                gate.accumulatedRealSeconds(),
                gate.running(),
                gate.lastStartedAt(),
                gate.lastHeartbeatAt(),
                staleAfterSeconds,
                LocalDateTime.now()
        );
    }

    private MarketSessionFenceRow lockFenceForUpdate(String marketType, String symbol) {
        return jdbcClient.sql(
                        """
                        select symbol,
                               business_date,
                               session_epoch,
                               session_state
                          from stock_market_session_fence
                         where market_type = ?
                           and symbol = ?
                         for update
                        """
                )
                .param(marketType)
                .param(symbol)
                .query((rs, rowNum) -> mapFence(rs))
                .optional()
                .orElse(null);
    }

    private MarketSessionFenceRow mapFence(java.sql.ResultSet rs) throws SQLException {
        return new MarketSessionFenceRow(
                rs.getString("symbol"),
                rs.getObject("business_date", LocalDate.class),
                rs.getLong("session_epoch"),
                rs.getString("session_state")
        );
    }

    private List<MarketConfigRow> findAllMarketConfigs() {
        List<MarketConfigRow> rows = new java.util.ArrayList<>();
        rows.addAll(findMarketConfigs(ORDER_BOOK, null));
        rows.addAll(findMarketConfigs(VIRTUAL_PRICE, null));
        return rows.stream()
                .sorted(Comparator.comparing(MarketConfigRow::marketType).thenComparing(MarketConfigRow::symbol))
                .toList();
    }

    private List<MarketConfigRow> findMarketConfigs(String marketType, String symbol) {
        String table = ORDER_BOOK.equals(marketType)
                ? "stock_order_book_market_config"
                : "stock_virtual_market_config";
        String symbolPredicate = symbol == null ? "" : "where symbol = :symbol";
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                """
                select symbol, enabled, market_status, updated_at
                  from %s
                  %s
                 order by symbol asc
                """.formatted(table, symbolPredicate)
        );
        if (symbol != null) {
            statement = statement.param("symbol", symbol);
        }
        return statement.query((rs, rowNum) -> new MarketConfigRow(
                marketType,
                rs.getString("symbol"),
                rs.getBoolean("enabled"),
                rs.getString("market_status"),
                rs.getObject("updated_at", LocalDateTime.class)
        )).list();
    }

    private int updateMarketConfigStatus(MarketConfigRow config, String targetStatus, LocalDateTime changedAt) {
        if (targetStatus.equals(config.marketStatus())) {
            return 0;
        }
        String table = ORDER_BOOK.equals(config.marketType())
                ? "stock_order_book_market_config"
                : "stock_virtual_market_config";
        return jdbcClient.sql(
                        "update %s set market_status = ?, updated_at = ? where symbol = ?"
                                .formatted(table)
                )
                .param(targetStatus)
                .param(changedAt)
                .param(config.symbol())
                .update();
    }

    private int closeOpenMarketConfigs(LocalDateTime changedAt) {
        int changedCount = 0;
        for (String table : List.of("stock_order_book_market_config", "stock_virtual_market_config")) {
            changedCount += jdbcClient.sql(
                            """
                            update %s
                               set market_status = 'CLOSED',
                                   updated_at = ?
                             where enabled = true
                               and market_status = 'OPEN'
                            """.formatted(table)
                    )
                    .param(changedAt)
                    .update();
        }
        return changedCount;
    }

    private MarketBusinessState ensureBusinessState(LocalDate rawSimulationDate, LocalDateTime changedAt) {
        MarketBusinessState state = findBusinessStateForUpdate();
        if (state != null) {
            return state;
        }
        LocalDate initialActiveDate = initialActiveBusinessDate(rawSimulationDate);
        try {
            jdbcClient.sql(
                            """
                            insert into stock_market_business_state(
                                state_id,
                                active_business_date,
                                preparing_business_date,
                                raw_simulation_date,
                                version,
                                created_at,
                                updated_at
                            )
                            values (?, ?, null, ?, 0, ?, ?)
                            """
                    )
                    .param(DEFAULT_STATE_ID)
                    .param(initialActiveDate)
                    .param(rawSimulationDate)
                    .param(changedAt)
                    .param(changedAt)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // A competing coordinator initialized the singleton row first.
        }
        MarketBusinessState inserted = findBusinessStateForUpdate();
        if (inserted == null) {
            throw new IllegalStateException("Unable to initialize stock market business state");
        }
        return inserted;
    }

    private LocalDate initialActiveBusinessDate(LocalDate rawSimulationDate) {
        SimulationMarketSession session = simulationMarketSessionService.currentSession();
        if (session != SimulationMarketSession.PRE_OPEN) {
            return rawSimulationDate;
        }
        LocalDate previousDate = rawSimulationDate.minusDays(1);
        LocalDate baseDate = simulationMarketSessionService.baseSimulationDate();
        return previousDate.isBefore(baseDate) ? baseDate : previousDate;
    }

    private void updateBusinessStateForOpen(LocalDate businessDate, LocalDateTime changedAt) {
        jdbcClient.sql(
                        """
                        update stock_market_business_state
                           set active_business_date = ?,
                               preparing_business_date = null,
                               raw_simulation_date = ?,
                               version = version + 1,
                               updated_at = ?
                         where state_id = ?
                           and (active_business_date <> ?
                                or preparing_business_date is not null
                                or raw_simulation_date <> ?)
                        """
                )
                .param(businessDate)
                .param(businessDate)
                .param(changedAt)
                .param(DEFAULT_STATE_ID)
                .param(businessDate)
                .param(businessDate)
                .update();
    }

    private void updateRawSimulationDate(LocalDate rawSimulationDate, LocalDateTime changedAt) {
        jdbcClient.sql(
                        """
                        update stock_market_business_state
                           set raw_simulation_date = ?,
                               version = version + 1,
                               updated_at = ?
                         where state_id = ?
                           and raw_simulation_date <> ?
                        """
                )
                .param(rawSimulationDate)
                .param(changedAt)
                .param(DEFAULT_STATE_ID)
                .param(rawSimulationDate)
                .update();
    }

    private void updatePreparingBusinessDate(
            LocalDate activeBusinessDate,
            LocalDate rawSimulationDate,
            LocalDateTime changedAt
    ) {
        LocalDate nextBusinessDate = activeBusinessDate.plusDays(1);
        LocalDate preparingBusinessDate = nextBusinessDate;
        jdbcClient.sql(
                        """
                        update stock_market_business_state
                           set preparing_business_date = ?,
                               raw_simulation_date = ?,
                               version = version + 1,
                               updated_at = ?
                         where state_id = ?
                           and (preparing_business_date is null
                                or preparing_business_date <> ?
                                or raw_simulation_date <> ?)
                        """
                )
                .param(preparingBusinessDate)
                .param(rawSimulationDate)
                .param(changedAt)
                .param(DEFAULT_STATE_ID)
                .param(preparingBusinessDate)
                .param(rawSimulationDate)
                .update();
    }

    private MarketBusinessState findBusinessState() {
        return jdbcClient.sql(
                        """
                        select active_business_date,
                               preparing_business_date,
                               raw_simulation_date,
                               version
                          from stock_market_business_state
                         where state_id = ?
                        """
                )
                .param(DEFAULT_STATE_ID)
                .query((rs, rowNum) -> mapBusinessState(rs))
                .optional()
                .orElse(null);
    }

    private MarketBusinessState findBusinessStateForUpdate() {
        return jdbcClient.sql(
                        """
                        select active_business_date,
                               preparing_business_date,
                               raw_simulation_date,
                               version
                          from stock_market_business_state
                         where state_id = ?
                         for update
                        """
                )
                .param(DEFAULT_STATE_ID)
                .query((rs, rowNum) -> mapBusinessState(rs))
                .optional()
                .orElse(null);
    }

    private MarketBusinessState mapBusinessState(java.sql.ResultSet rs) throws SQLException {
        return new MarketBusinessState(
                rs.getObject("active_business_date", LocalDate.class),
                rs.getObject("preparing_business_date", LocalDate.class),
                rs.getObject("raw_simulation_date", LocalDate.class),
                rs.getLong("version")
        );
    }

    private boolean isMySql(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) this::databaseProductName
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    private String databaseProductName(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName();
    }

    private boolean regularStateMatches(
            MarketConfigFenceStateRow row,
            LocalDate businessDate,
            LocalDateTime openBoundary
    ) {
        boolean shouldOpen = row.enabled()
                && (OPEN.equals(row.marketStatus())
                || ((CLOSED.equals(row.marketStatus()) || "CIRCUIT_BREAKER".equals(row.marketStatus()))
                && row.updatedAt().isBefore(openBoundary)));
        if (!businessDate.equals(row.fenceBusinessDate())) {
            return false;
        }
        if (shouldOpen) {
            return OPEN.equals(row.marketStatus()) && OPEN.equals(row.fenceState());
        }
        return CLOSED.equals(row.fenceState());
    }

    private String normalizeNullableSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOperation(String operation) {
        return operation == null || operation.isBlank() ? "account-ledger mutation" : operation.trim();
    }

    private record MarketSessionFenceRow(
            String symbol,
            LocalDate businessDate,
            long sessionEpoch,
            String sessionState
    ) {
    }

    private record MarketSessionGateRow(
            MarketSessionFenceRow fence,
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate,
            LocalDate baseSimulationDate,
            int realSecondsPerSimulationDay,
            long accumulatedRealSeconds,
            boolean running,
            LocalDateTime lastStartedAt,
            LocalDateTime lastHeartbeatAt
    ) {
    }

    private record MarketBusinessState(
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate,
            long version
    ) {
    }

    private record LedgerMutationBlockers(boolean freezePending, boolean marketOpen) {
    }

    private record MarketConfigRow(
            String marketType,
            String symbol,
            boolean enabled,
            String marketStatus,
            LocalDateTime updatedAt
    ) {
    }

    private record MarketConfigFenceStateRow(
            boolean enabled,
            String marketStatus,
            LocalDateTime updatedAt,
            LocalDate fenceBusinessDate,
            String fenceState
    ) {
    }

    public record MarketSessionApproval(
            LocalDate businessDate,
            Map<String, Long> sessionEpochs,
            LocalDateTime businessEffectiveAt
    ) {
    }

    public record MarketBusinessStateSnapshot(
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate,
            long version
    ) {
    }
}
