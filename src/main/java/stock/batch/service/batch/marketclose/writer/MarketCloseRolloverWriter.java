package stock.batch.service.batch.marketclose.writer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;

@Component
public class MarketCloseRolloverWriter {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final String openOrderCaptureTable;
    private final String lockedOrderTable;
    private final String executionRangeTable;
    private final String executionCandleTable;
    private final String executionAccountReportTable;

    public MarketCloseRolloverWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        boolean mySql = isMySql(jdbcTemplate);
        this.openOrderCaptureTable = mySql
                ? "stock_order force index (idx_stock_order_market_status_symbol)"
                : "stock_order";
        this.lockedOrderTable = mySql ? "stock_order force index (primary)" : "stock_order";
        this.executionRangeTable = mySql
                ? "stock_execution force index (idx_stock_execution_source_symbol_time)"
                : "stock_execution";
        this.executionCandleTable = mySql
                ? "stock_execution force index (idx_stock_execution_candle)"
                : "stock_execution";
        this.executionAccountReportTable = mySql
                ? "stock_execution force index (idx_stock_execution_market_report_flow)"
                : "stock_execution";
    }

    public long createCloseRun(String symbol, LocalDate businessDate, LocalDateTime closedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into stock_market_close_run(
                        symbol, business_date, closed_at, status,
                        cancelled_order_count, holding_snapshot_count, price_rollover_count,
                        created_at, completed_at
                    )
                    values (?, ?, ?, 'STARTED', 0, 0, 0, ?, null)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, symbol);
            ps.setObject(2, businessDate);
            ps.setObject(3, closedAt);
            ps.setObject(4, closedAt);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Market close run id was not generated");
        }
        return key.longValue();
    }

    public boolean hasCompletedFullCloseRun(LocalDate businessDate) {
        Integer count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_market_close_run
                         where business_date = ?
                           and symbol is null
                           and status = 'COMPLETED'
                        """
                )
                .param(businessDate)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /**
     * Resolves the order-book symbol-lock set from control tables only. Open orders and holdings
     * can grow with trading volume, while a symbol that can still execute must already have an
     * order-book config or session fence. Stale unregistered orders are still captured and
     * cancelled by the close cohort, but must not force a full ledger scan just to build locks.
     */
    public List<String> findCloseLockSymbols(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            return List.of(symbol);
        }
        return jdbcClient.sql(
                """
                select symbol
                  from (
                       select symbol
                         from stock_market_session_fence
                        where market_type = 'ORDER_BOOK'
                       union
                       select symbol
                         from stock_order_book_market_config
                       union
                       select symbol
                         from stock_order_book_instrument
                  ) close_symbols
                 where symbol is not null
                 order by symbol asc
                """
        )
                .query(String.class)
                .list();
    }

    public boolean isOrderBookMarketOpen(String symbol) {
        Integer count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order_book_market_config
                         where symbol = ?
                           and enabled = true
                           and market_status = 'OPEN'
                        """
                )
                .param(symbol)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /**
     * Selects a bounded cancellation cohort without acquiring a range lock. The caller locks
     * accounts and holdings first, then locks only these exact order primary keys. This keeps the
     * halt/circuit-breaker cleanup path aligned with the live execution lock order and avoids a
     * status-index next-key lock blocking concurrent order inserts.
     */
    public List<MarketCloseOrderRow> findOpenOrderBookOrderCandidates(String symbol, int limit) {
        return jdbcClient.sql(
                """
                select id,
                       account_id,
                       symbol,
                       side,
                       quantity - filled_quantity as remaining_quantity,
                       reserved_cash
                  from %s
                 where market_type = 'ORDER_BOOK'
                   and symbol = ?
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and quantity > filled_quantity
                 order by status asc, symbol asc, id asc
                 limit ?
                """.formatted(openOrderCaptureTable)
        )
                .param(symbol)
                .param(limit)
                .query((rs, rowNum) -> new MarketCloseOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ))
                .list();
    }

    public void lockAccountsForUpdate(List<MarketCloseOrderRow> candidates) {
        Set<Long> accountIds = candidates.stream()
                .map(MarketCloseOrderRow::accountId)
                .collect(Collectors.toSet());
        if (accountIds.isEmpty()) {
            return;
        }
        jdbcClient.sql(
                        """
                        select id
                          from stock_account
                         where id in (:accountIds)
                         order by id asc
                         for update
                        """
                )
                .param("accountIds", accountIds)
                .query(Long.class)
                .list();
    }

    public void lockSellHoldingsForUpdate(String symbol, List<MarketCloseOrderRow> candidates) {
        Set<Long> sellAccountIds = candidates.stream()
                .filter(order -> "SELL".equals(order.side()))
                .map(MarketCloseOrderRow::accountId)
                .collect(Collectors.toSet());
        if (sellAccountIds.isEmpty()) {
            return;
        }
        jdbcClient.sql(
                        """
                        select id
                          from stock_holding
                         where symbol = :symbol
                           and account_id in (:accountIds)
                         order by account_id asc, symbol asc
                         for update
                        """
                )
                .param("symbol", symbol)
                .param("accountIds", sellAccountIds)
                .query(Long.class)
                .list();
    }

    public void lockSellHoldingsForUpdate(List<MarketCloseOrderRow> candidates) {
        List<HoldingLockKey> holdingKeys = candidates.stream()
                .filter(order -> "SELL".equals(order.side()))
                .map(order -> new HoldingLockKey(order.accountId(), order.symbol()))
                .distinct()
                .sorted(Comparator.comparingLong(HoldingLockKey::accountId)
                        .thenComparing(HoldingLockKey::symbol))
                .toList();
        if (holdingKeys.isEmpty()) {
            return;
        }

        String tupleParameters = java.util.stream.IntStream.range(0, holdingKeys.size())
                .mapToObj(index -> "(:accountId%d, :symbol%d)".formatted(index, index))
                .collect(Collectors.joining(", "));
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                        """
                        select id
                          from stock_holding
                         where (account_id, symbol) in (%s)
                         order by account_id asc, symbol asc
                         for update
                        """.formatted(tupleParameters)
                );
        for (int index = 0; index < holdingKeys.size(); index++) {
            HoldingLockKey key = holdingKeys.get(index);
            statement = statement
                    .param("accountId" + index, key.accountId())
                    .param("symbol" + index, key.symbol());
        }
        statement.query(Long.class).list();
    }

    /**
     * Locks exact primary keys only. The status predicate is deliberately evaluated after the PK
     * locks are acquired so MySQL cannot choose the open-status secondary index and create a gap
     * lock that stalls new order inserts.
     */
    public List<MarketCloseOrderRow> lockOpenOrderBookOrdersForUpdate(List<MarketCloseOrderRow> candidates) {
        Set<Long> orderIds = candidates.stream()
                .map(MarketCloseOrderRow::id)
                .collect(Collectors.toSet());
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                        """
                        select id,
                               account_id,
                               symbol,
                               side,
                               status,
                               quantity - filled_quantity as remaining_quantity,
                               reserved_cash
                          from %s
                         where id in (:orderIds)
                         order by id asc
                         for update
                        """.formatted(lockedOrderTable)
                )
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> new LockedOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getString("status"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ))
                .list()
                .stream()
                .filter(LockedOrderRow::isOpen)
                .map(LockedOrderRow::toMarketCloseOrderRow)
                .toList();
    }

    /**
     * Finds only symbols that still have an open order by using the existing
     * (market_type, status, symbol) index. This avoids walking the full historical order PK from
     * zero on every close and does not add another write-amplifying stock_order index.
     */
    public List<String> findOpenOrderCaptureSymbols() {
        return jdbcClient.sql(
                        """
                        select distinct symbol
                          from %s
                         where market_type = 'ORDER_BOOK'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                         order by symbol asc
                        """.formatted(openOrderCaptureTable)
                )
                .query(String.class)
                .list();
    }

    public long findLastCapturedOrderId(long closeCycleId, String symbol, String sourceOrderStatus) {
        Long orderId = jdbcClient.sql(
                        """
                        select coalesce(max(order_id), 0)
                          from stock_close_open_order_snapshot
                         where close_cycle_id = ?
                           and symbol = ?
                           and source_order_status = ?
                        """
                )
                .param(closeCycleId)
                .param(symbol)
                .param(sourceOrderStatus)
                .query(Long.class)
                .single();
        return orderId == null ? 0L : orderId;
    }

    /**
     * Captures a bounded primary-key window after the session fence has closed order entry.
     * The committed max(order_id) in the immutable snapshot is the restart checkpoint, so a
     * process crash never requires rescanning or reinserting the already frozen prefix.
     */
    public int captureOpenOrdersChunk(
            long closeCycleId,
            long closeRunId,
            String symbol,
            String sourceOrderStatus,
            LocalDateTime capturedAt,
            long afterOrderId,
            int limit
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for bounded open-order capture");
        }
        if (!"PENDING".equals(sourceOrderStatus) && !"PARTIALLY_FILLED".equals(sourceOrderStatus)) {
            throw new IllegalArgumentException("Unsupported open-order capture status: " + sourceOrderStatus);
        }
        return jdbcClient.sql(
                        """
                        insert into stock_close_open_order_snapshot(
                            close_cycle_id, close_run_id, order_id, account_id, symbol, side,
                            source_order_status, remaining_quantity, reserved_cash,
                            captured_at, released_at
                        )
                        select :closeCycleId, :closeRunId, id, account_id, symbol, side,
                               :sourceOrderStatus, quantity - filled_quantity, reserved_cash,
                               :capturedAt, null
                          from %s
                         where id > :afterOrderId
                           and market_type = 'ORDER_BOOK'
                           and status = :sourceOrderStatus
                           and quantity > filled_quantity
                           and symbol = :symbol
                         order by id asc
                         limit :limit
                        """.formatted(openOrderCaptureTable)
                )
                .param("closeCycleId", closeCycleId)
                .param("closeRunId", closeRunId)
                .param("symbol", symbol)
                .param("sourceOrderStatus", sourceOrderStatus)
                .param("capturedAt", capturedAt)
                .param("afterOrderId", afterOrderId)
                .param("limit", Math.max(1, limit))
                .update();
    }

    public long countOpenOrderSummaries(long closeCycleId) {
        Long count = jdbcClient.sql(
                        "select count(*) from stock_close_open_order_summary where close_cycle_id = ?"
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public ReleasedReservationTotals sumReleasedReservations(long closeCycleId) {
        return jdbcClient.sql(
                        """
                        select coalesce(sum(pre_cancel_reserved_buy_cash), 0) as released_buy_cash,
                               coalesce(sum(pre_cancel_reserved_sell_quantity), 0) as released_sell_quantity
                          from stock_close_open_order_summary
                         where close_cycle_id = ?
                        """
                )
                .param(closeCycleId)
                .query((rs, rowNum) -> new ReleasedReservationTotals(
                        rs.getBigDecimal("released_buy_cash"),
                        rs.getLong("released_sell_quantity")
                ))
                .single();
    }

    public int snapshotOpenOrderSummary(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDateTime snapshotAt
    ) {
        String instrumentSymbolPredicate = symbol == null ? "" : "where symbol = :symbol";
        String snapshotSymbolPredicate = symbol == null ? "" : "and symbol = :symbol";
        String sql = """
                insert into stock_close_open_order_summary(
                    close_cycle_id, close_run_id, symbol,
                    pre_cancel_open_order_count, pre_cancel_buy_order_count, pre_cancel_sell_order_count,
                    pre_cancel_remaining_buy_quantity, pre_cancel_remaining_sell_quantity,
                    pre_cancel_reserved_buy_cash, pre_cancel_reserved_sell_quantity,
                    post_cancel_open_order_count, reconciliation_status, snapshot_at, created_at
                )
                select :closeCycleId, :closeRunId, symbols.symbol,
                       coalesce(orders.open_order_count, 0),
                       coalesce(orders.buy_order_count, 0),
                       coalesce(orders.sell_order_count, 0),
                       coalesce(orders.remaining_buy_quantity, 0),
                       coalesce(orders.remaining_sell_quantity, 0),
                       coalesce(orders.reserved_buy_cash, 0),
                       coalesce(orders.remaining_sell_quantity, 0),
                       0, 'PENDING', :snapshotAt, :snapshotAt
                  from (
                       select symbol
                         from stock_order_book_instrument
                         %s
                       union
                       select symbol
                         from stock_close_open_order_snapshot
                        where close_cycle_id = :closeCycleId
                          %s
                  ) symbols
                  left join (
                       select symbol,
                              count(*) as open_order_count,
                              sum(case when side = 'BUY' then 1 else 0 end) as buy_order_count,
                              sum(case when side = 'SELL' then 1 else 0 end) as sell_order_count,
                              sum(case when side = 'BUY' then remaining_quantity else 0 end) as remaining_buy_quantity,
                              sum(case when side = 'SELL' then remaining_quantity else 0 end) as remaining_sell_quantity,
                              sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash
                         from stock_close_open_order_snapshot
                        where close_cycle_id = :closeCycleId
                          %s
                        group by symbol
                  ) orders on orders.symbol = symbols.symbol
                """.formatted(
                instrumentSymbolPredicate,
                snapshotSymbolPredicate,
                snapshotSymbolPredicate
        );
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("closeCycleId", closeCycleId)
                .param("closeRunId", closeRunId)
                .param("snapshotAt", snapshotAt);
        if (symbol != null) {
            statement = statement.param("symbol", symbol);
        }
        return statement.update();
    }

    public long countAccountSnapshots(long closeCycleId) {
        Long count = jdbcClient.sql(
                        "select count(*) from stock_close_account_snapshot where close_cycle_id = ?"
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public long findLastAccountSnapshotId(long closeCycleId) {
        Long accountId = jdbcClient.sql(
                        """
                        select coalesce(max(account_id), 0)
                          from stock_close_account_snapshot
                         where close_cycle_id = ?
                        """
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return accountId == null ? 0L : accountId;
    }

    public List<Long> findAccountSnapshotCandidates(long afterAccountId, int limit) {
        return jdbcClient.sql(
                        """
                        select id
                          from stock_account
                         where id > ?
                         order by id asc
                         limit ?
                        """
                )
                .param(afterAccountId)
                .param(Math.max(1, limit))
                .query(Long.class)
                .list();
    }

    public int snapshotAccountsForAccounts(
            long closeCycleId,
            long closeRunId,
            LocalDateTime snapshotAt,
            Long previousCloseCycleId,
            long previousCashFlowWatermarkId,
            long currentCashFlowWatermarkId,
            List<Long> accountIds
    ) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        insert into stock_close_account_snapshot(
                            close_cycle_id, close_run_id, account_id, user_key, account_status,
                            participant_category,
                            settlement_target, pre_cancel_cash, pre_cancel_order_reserved_cash,
                            subscription_reserved_cash, post_cancel_cash, external_net_cash_flow,
                            cash_flow_watermark_id, holding_market_value, holding_quantity,
                            reserved_sell_quantity, holding_position_count,
                            reconciliation_status, snapshot_at, created_at
                        )
                        select :closeCycleId, :closeRunId, a.id, a.user_key, a.status,
                               case
                                 when a.user_key like 'stock-listing-%' then 'LISTING_UNDERWRITER'
                                 when participant.user_key is not null then 'AUTO_PARTICIPANT'
                                 else 'MANUAL_PARTICIPANT'
                               end,
                               case
                                 when a.status = 'ACTIVE'
                                  and a.user_key is not null
                                  and a.user_key not like 'stock-listing-%'
                                 then true else false
                               end,
                               a.cash_balance,
                               coalesce(open_buy.reserved_cash, 0),
                               coalesce(subscription.reserved_cash, 0),
                               null,
                               coalesce(previous.external_net_cash_flow, 0)
                                 + coalesce(cash_flow.external_net_cash_flow_delta, 0),
                               :currentCashFlowWatermarkId,
                               coalesce(holding.market_value, 0),
                               coalesce(holding.holding_quantity, 0),
                               coalesce(holding.reserved_sell_quantity, 0),
                               coalesce(holding.holding_position_count, 0),
                               'PENDING', :snapshotAt, :snapshotAt
                          from stock_account a
                          left join stock_auto_participant participant
                            on participant.user_key = a.user_key
                          left join stock_close_account_snapshot previous
                            on previous.close_cycle_id = :previousCloseCycleId
                           and previous.account_id = a.id
                          left join (
                               select account_id, sum(reserved_cash) as reserved_cash
                                 from stock_close_open_order_snapshot
                                where close_cycle_id = :closeCycleId
                                  and side = 'BUY'
                                  and account_id in (:accountIds)
                                group by account_id
                          ) open_buy on open_buy.account_id = a.id
                          left join (
                               select account_id, sum(subscribed_cash_amount) as reserved_cash
                                 from stock_corporate_action_entitlement
                                where status = 'SUBSCRIBED'
                                  and subscribed_cash_amount is not null
                                  and account_id in (:accountIds)
                                group by account_id
                          ) subscription on subscription.account_id = a.id
                          left join (
                               select account_id,
                                      sum(case
                                            when flow_type = 'DEPOSIT' and reason <> 'DIVIDEND_PAYMENT' then amount
                                            when flow_type = 'WITHDRAW' and reason <> 'CAPITAL_INCREASE_SUBSCRIPTION' then -amount
                                            else 0
                                          end) as external_net_cash_flow_delta
                                 from stock_account_cash_flow
                                where id > :previousCashFlowWatermarkId
                                  and id <= :currentCashFlowWatermarkId
                                  and account_id in (:accountIds)
                                group by account_id
                          ) cash_flow on cash_flow.account_id = a.id
                          left join (
                               select account_id,
                                      sum(quantity * coalesce(evaluation_price, average_price)) as market_value,
                                      sum(quantity) as holding_quantity,
                                      sum(reserved_quantity) as reserved_sell_quantity,
                                      sum(case when quantity > 0 then 1 else 0 end) as holding_position_count
                                 from stock_holding_snapshot
                                where close_cycle_id = :closeCycleId
                                  and account_id in (:accountIds)
                                group by account_id
                          ) holding on holding.account_id = a.id
                         where a.id in (:accountIds)
                         order by a.id asc
                        """
                )
                .param("closeCycleId", closeCycleId)
                .param("closeRunId", closeRunId)
                .param("snapshotAt", snapshotAt)
                .param("previousCloseCycleId", previousCloseCycleId == null ? -1L : previousCloseCycleId)
                .param("previousCashFlowWatermarkId", previousCashFlowWatermarkId)
                .param("currentCashFlowWatermarkId", currentCashFlowWatermarkId)
                .param("accountIds", accountIds)
                .update();
    }

    public Long findPreviousFrozenFullMarketCycleId(long closeCycleId) {
        List<Long> cycleIds = jdbcTemplate.queryForList(
                """
                select previous_cycle.id
                  from stock_post_close_cycle current_cycle
                  join stock_post_close_cycle previous_cycle
                    on previous_cycle.scope_type = 'FULL_MARKET'
                   and previous_cycle.scope_key = 'ALL'
                   and previous_cycle.business_date < current_cycle.business_date
                   and previous_cycle.close_run_id is not null
                   and previous_cycle.phase in (
                       'LEDGER_FROZEN',
                       'PORTFOLIO_SETTLED',
                       'OVERNIGHT_CASH_APPLIED',
                       'CORPORATE_CASH_APPLIED',
                       'REPORTS_AGGREGATED',
                       'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                       'MARKET_DATA_PREPARED',
                       'AUTO_MARKET_PREPARED',
                       'READY_TO_OPEN',
                       'COMPLETED'
                   )
                 where current_cycle.id = ?
                 order by previous_cycle.business_date desc, previous_cycle.id desc
                 limit 1
                """,
                Long.class,
                closeCycleId
        );
        return cycleIds.isEmpty() ? null : cycleIds.getFirst();
    }

    public long findCashFlowWatermark(long closeCycleId) {
        Long watermark = jdbcTemplate.queryForObject(
                """
                select coalesce(max(cash_flow_watermark_id), 0)
                  from stock_close_account_snapshot
                 where close_cycle_id = ?
                """,
                Long.class,
                closeCycleId
        );
        return watermark == null ? 0L : watermark;
    }

    public long findCurrentCashFlowWatermark() {
        Long watermark = jdbcTemplate.queryForObject(
                "select coalesce(max(id), 0) from stock_account_cash_flow",
                Long.class
        );
        return watermark == null ? 0L : watermark;
    }

    public int snapshotPrices(
            long closeCycleId,
            long closeRunId,
            LocalDateTime snapshotAt
    ) {
        return jdbcTemplate.update(
                """
                insert into stock_close_price_snapshot(
                    close_cycle_id, close_run_id, symbol, close_price, previous_close,
                    price_time, price_provider, last_execution_id, order_book_symbol,
                    snapshot_at, created_at
                )
                select ?, ?, symbols.symbol,
                       coalesce(price.current_price, holdings.fallback_price, 0),
                       coalesce(price.previous_close, price.current_price, holdings.fallback_price, 0),
                       price.price_time,
                       price.provider,
                       null,
                       case when instrument.symbol is not null then true else false end,
                       ?, ?
                  from (
                       select symbol from stock_price
                       union
                       select symbol
                         from stock_holding_snapshot
                        where close_cycle_id = ?
                       union
                       select symbol from stock_order_book_instrument where enabled = true
                  ) symbols
                  left join stock_price price on price.symbol = symbols.symbol
                  left join stock_order_book_instrument instrument on instrument.symbol = symbols.symbol
                  left join (
                       select symbol, max(average_price) as fallback_price
                         from stock_holding_snapshot
                        where close_cycle_id = ?
                        group by symbol
                  ) holdings on holdings.symbol = symbols.symbol
                """,
                closeCycleId,
                closeRunId,
                snapshotAt,
                snapshotAt,
                closeCycleId,
                closeCycleId
        );
    }

    public long countPriceSnapshots(long closeCycleId) {
        Long count = jdbcClient.sql(
                        "select count(*) from stock_close_price_snapshot where close_cycle_id = ?"
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public List<MarketCloseOrderRow> findUnreleasedCapturedOrderCandidates(long closeCycleId, int limit) {
        return jdbcClient.sql(
                        """
                        select order_id,
                               account_id,
                               symbol,
                               side,
                               remaining_quantity,
                               reserved_cash
                          from stock_close_open_order_snapshot
                         where close_cycle_id = ?
                           and released_at is null
                         order by order_id asc
                         limit ?
                        """
                )
                .param(closeCycleId)
                .param(Math.max(1, limit))
                .query((rs, rowNum) -> new MarketCloseOrderRow(
                        rs.getLong("order_id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ))
                .list();
    }

    public int cancelCapturedOrders(
            long closeCycleId,
            List<Long> orderIds,
            LocalDateTime updatedAt
    ) {
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        update %s
                           set status = 'CANCELLED',
                               reserved_cash = 0,
                               updated_at = :updatedAt
                         where id in (:orderIds)
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and exists (
                               select 1
                                 from stock_close_open_order_snapshot snapshot
                                where snapshot.close_cycle_id = :closeCycleId
                                  and snapshot.order_id = stock_order.id
                                  and snapshot.released_at is null
                           )
                        """.formatted(lockedOrderTable)
                )
                .param("updatedAt", updatedAt)
                .param("orderIds", orderIds)
                .param("closeCycleId", closeCycleId)
                .update();
    }

    public int markCapturedOrdersReleased(
            long closeCycleId,
            List<Long> orderIds,
            LocalDateTime releasedAt
    ) {
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        update stock_close_open_order_snapshot
                           set released_at = :releasedAt
                         where close_cycle_id = :closeCycleId
                           and order_id in (:orderIds)
                           and released_at is null
                        """
                )
                .param("releasedAt", releasedAt)
                .param("closeCycleId", closeCycleId)
                .param("orderIds", orderIds)
                .update();
    }

    public long countCapturedOrders(long closeCycleId) {
        Long count = jdbcClient.sql(
                        "select count(*) from stock_close_open_order_snapshot where close_cycle_id = ?"
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public long countRemainingCapturedOrders(long closeCycleId) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_close_open_order_snapshot
                         where close_cycle_id = ?
                           and released_at is null
                        """
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public List<Long> findPendingAccountReconciliationCandidates(
            long closeCycleId,
            long afterAccountId,
            int limit
    ) {
        return jdbcClient.sql(
                        """
                        select account_id
                          from stock_close_account_snapshot
                         where close_cycle_id = ?
                           and account_id > ?
                           and reconciliation_status = 'PENDING'
                         order by account_id asc
                         limit ?
                        """
                )
                .param(closeCycleId)
                .param(afterAccountId)
                .param(Math.max(1, limit))
                .query(Long.class)
                .list();
    }

    public int completeAccountSnapshotReconciliation(
            long closeCycleId,
            List<Long> accountIds
    ) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        update stock_close_account_snapshot s
                           set post_cancel_cash = (
                                   select a.cash_balance
                                     from stock_account a
                                    where a.id = s.account_id
                               ),
                               reconciliation_status = case
                                   when (
                                       select a.cash_balance
                                         from stock_account a
                                        where a.id = s.account_id
                                   ) = s.pre_cancel_cash + s.pre_cancel_order_reserved_cash
                                   then 'MATCHED'
                                   else 'MISMATCHED'
                               end
                         where s.close_cycle_id = :closeCycleId
                           and s.account_id in (:accountIds)
                           and s.reconciliation_status = 'PENDING'
                        """
                )
                .param("closeCycleId", closeCycleId)
                .param("accountIds", accountIds)
                .update();
    }

    public int completeOpenOrderSummaryReconciliation(long closeCycleId) {
        return jdbcTemplate.update(
                """
                update stock_close_open_order_summary
                   set post_cancel_open_order_count = 0,
                       reconciliation_status = 'MATCHED'
                 where close_cycle_id = ?
                   and reconciliation_status = 'PENDING'
                """,
                closeCycleId
        );
    }

    public long countSnapshotReconciliationMismatches(long closeCycleId) {
        Long count = jdbcClient.sql(
                        """
                        select (
                            select count(*)
                              from stock_close_account_snapshot
                             where close_cycle_id = ?
                               and reconciliation_status <> 'MATCHED'
                        ) + (
                            select count(*)
                              from stock_close_open_order_summary
                             where close_cycle_id = ?
                               and reconciliation_status <> 'MATCHED'
                        )
                        """
                )
                .param(closeCycleId)
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public long countSettlementTargetAccounts(long closeCycleId) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_close_account_snapshot
                         where close_cycle_id = ?
                           and settlement_target = true
                        """
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /**
     * Persists the one-time close counters consumed by operations screens. Keeping these
     * counters beside the cycle prevents every UI poll from rescanning order, execution, or
     * snapshot history as trading volume grows.
     */
    public void insertCycleMetrics(
            long closeCycleId,
            long closeRunId,
            long capturedOpenOrderCount,
            long cancelledOrderCount,
            BigDecimal releasedBuyCash,
            long releasedSellQuantity,
            long settlementTargetAccountCount,
            long accountSnapshotCount,
            long holdingSnapshotCount,
            long priceSnapshotCount,
            long openOrderSummaryCount,
            long reconciliationMismatchCount,
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle_metric(
                    close_cycle_id, close_run_id,
                    captured_open_order_count, cancelled_order_count,
                    released_buy_cash, released_sell_quantity,
                    settlement_target_account_count, account_snapshot_count,
                    holding_snapshot_count, price_snapshot_count,
                    open_order_summary_count, reconciliation_mismatch_count,
                    settled_account_count, settlement_missing_account_count,
                    updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                closeCycleId,
                closeRunId,
                capturedOpenOrderCount,
                cancelledOrderCount,
                releasedBuyCash,
                releasedSellQuantity,
                settlementTargetAccountCount,
                accountSnapshotCount,
                holdingSnapshotCount,
                priceSnapshotCount,
                openOrderSummaryCount,
                reconciliationMismatchCount,
                settlementTargetAccountCount,
                updatedAt
        );
    }

    public int cancelOrders(List<Long> orderIds, LocalDateTime updatedAt) {
        List<Long> orderedOrderIds = orderIds.stream().distinct().sorted().toList();
        if (orderedOrderIds.isEmpty()) {
            return 0;
        }
        if (orderedOrderIds.size() != orderIds.size()) {
            throw new IllegalArgumentException("Market-close cancellation chunk contains duplicate order ids");
        }
        String placeholders = String.join(",", Collections.nCopies(orderedOrderIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(orderedOrderIds.size() + 1);
        parameters.add(updatedAt);
        parameters.addAll(orderedOrderIds);
        return jdbcTemplate.update(
                """
                update %s
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where id in (%s)
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """.formatted(lockedOrderTable, placeholders),
                parameters.toArray()
        );
    }

    public int creditCashChunk(Map<Long, BigDecimal> cashByAccountId, LocalDateTime updatedAt) {
        if (cashByAccountId.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Long, BigDecimal>> entries = cashByAccountId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        String cashCases = String.join(" ", Collections.nCopies(entries.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(entries.size(), "?"));
        List<Object> parameters = new ArrayList<>(entries.size() * 3 + 1);
        for (Map.Entry<Long, BigDecimal> entry : entries) {
            parameters.add(entry.getKey());
            parameters.add(entry.getValue());
        }
        parameters.add(updatedAt);
        entries.forEach(entry -> parameters.add(entry.getKey()));
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                """.formatted(cashCases, placeholders),
                parameters.toArray()
        );
    }

    public int releaseReservedSellQuantityChunk(
            String symbol,
            Map<Long, Long> quantityByAccountId,
            LocalDateTime updatedAt
    ) {
        if (quantityByAccountId.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Long, Long>> entries = quantityByAccountId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        String quantityCases = String.join(" ", Collections.nCopies(entries.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(entries.size(), "?"));
        List<Object> parameters = new ArrayList<>(entries.size() * 3 + 2);
        for (Map.Entry<Long, Long> entry : entries) {
            parameters.add(entry.getKey());
            parameters.add(entry.getValue());
        }
        parameters.add(updatedAt);
        parameters.add(symbol);
        entries.forEach(entry -> parameters.add(entry.getKey()));
        return jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = greatest(
                           0,
                           reserved_quantity - case account_id %s else 0 end
                       ),
                       updated_at = ?
                 where symbol = ?
                   and account_id in (%s)
                """.formatted(quantityCases, placeholders),
                parameters.toArray()
        );
    }

    public int releaseReservedSellQuantityChunk(
            Map<HoldingReservationKey, Long> quantityByHolding,
            LocalDateTime updatedAt
    ) {
        if (quantityByHolding == null || quantityByHolding.isEmpty()) {
            return 0;
        }
        List<Map.Entry<HoldingReservationKey, Long>> entries = quantityByHolding.entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<HoldingReservationKey, Long> entry) ->
                                entry.getKey().accountId())
                        .thenComparing(entry -> entry.getKey().symbol()))
                .toList();
        String quantityCases = String.join(" ", Collections.nCopies(
                entries.size(),
                "when account_id = ? and symbol = ? then ?"
        ));
        String predicates = String.join(" or ", Collections.nCopies(
                entries.size(),
                "(account_id = ? and symbol = ?)"
        ));
        List<Object> parameters = new ArrayList<>(entries.size() * 5 + 1);
        for (Map.Entry<HoldingReservationKey, Long> entry : entries) {
            parameters.add(entry.getKey().accountId());
            parameters.add(entry.getKey().symbol());
            parameters.add(entry.getValue());
        }
        parameters.add(updatedAt);
        for (Map.Entry<HoldingReservationKey, Long> entry : entries) {
            parameters.add(entry.getKey().accountId());
            parameters.add(entry.getKey().symbol());
        }
        return jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = greatest(
                           0,
                           reserved_quantity - case %s else 0 end
                       ),
                       updated_at = ?
                 where %s
                """.formatted(quantityCases, predicates),
                parameters.toArray()
        );
    }

    public int rolloverClosingPrices(String symbol) {
        if (symbol != null) {
            return jdbcTemplate.update(
                    """
                    update stock_price
                       set previous_close = current_price
                     where previous_close <> current_price
                       and symbol = ?
                    """,
                    symbol
            );
        }
        return jdbcTemplate.update(
                """
                update stock_price
                   set previous_close = current_price
                 where previous_close <> current_price
                """
        );
    }

    public int snapshotDailyAccountExecutions(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDate simulationTradeDate,
            LocalDateTime snapshotAt,
            LocalDateTime executionRangeStart,
            LocalDateTime executionRangeEnd
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for bounded account report aggregation");
        }
        String sql = """
                insert into stock_execution_daily_account_snapshot(
                    close_run_id, symbol, simulation_trade_date, account_id, participant_category,
                    execution_count, buy_quantity, sell_quantity, buy_amount, sell_amount,
                    net_cash_flow, execution_amount, last_executed_at, created_at
                )
                select :closeRunId,
                       execution.symbol,
                       :simulationTradeDate,
                       execution.account_id,
                       account_snapshot.participant_category,
                       count(*),
                       sum(case when execution.side = 'BUY' then execution.quantity else 0 end),
                       sum(case when execution.side = 'SELL' then execution.quantity else 0 end),
                       sum(case when execution.side = 'BUY' then execution.gross_amount else 0 end),
                       sum(case when execution.side = 'SELL' then execution.gross_amount else 0 end),
                       sum(case when execution.side = 'BUY' then -execution.net_amount else execution.net_amount end),
                       sum(execution.gross_amount),
                       max(execution.executed_at),
                       :snapshotAt
                  from %s execution
                  join stock_close_account_snapshot account_snapshot
                    on account_snapshot.close_cycle_id = :closeCycleId
                   and account_snapshot.account_id = execution.account_id
                 where execution.source = 'INTERNAL_ORDER_BOOK'
                   and execution.symbol = :symbol
                   and execution.executed_at >= :executionRangeStart
                   and execution.executed_at < :executionRangeEnd
                 group by execution.symbol,
                          execution.account_id,
                          account_snapshot.participant_category
                """.formatted(executionAccountReportTable);
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("closeCycleId", closeCycleId)
                .param("closeRunId", closeRunId)
                .param("simulationTradeDate", simulationTradeDate)
                .param("snapshotAt", snapshotAt)
                .param("executionRangeStart", executionRangeStart)
                .param("executionRangeEnd", executionRangeEnd)
                .param("symbol", symbol);
        return statement.update();
    }

    public int deleteDailyAccountExecutionSnapshot(long closeRunId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for bounded account report replacement");
        }
        return jdbcTemplate.update(
                """
                delete from stock_execution_daily_account_snapshot
                 where close_run_id = ?
                   and symbol = ?
                """,
                closeRunId,
                symbol
        );
    }

    public int rebuildExecutionAccountDaySummary(LocalDate businessDate, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                "delete from stock_execution_account_day_summary where simulation_trade_date = ?",
                businessDate
        );
        LocalDateTime rangeStart = businessDate.atStartOfDay();
        LocalDateTime rangeEnd = businessDate.plusDays(1).atStartOfDay();
        return jdbcTemplate.update(
                """
                insert into stock_execution_account_day_summary(
                    simulation_trade_date, account_id, execution_count, buy_quantity,
                    sell_quantity, gross_amount, buy_gross_amount, sell_gross_amount,
                    buy_net_amount, sell_net_amount, fee_amount, tax_amount,
                    realized_profit, last_executed_at, updated_at
                )
                select ?, execution.account_id,
                       count(*),
                       sum(case when execution.side = 'BUY' then execution.quantity else 0 end),
                       sum(case when execution.side = 'SELL' then execution.quantity else 0 end),
                       sum(execution.gross_amount),
                       sum(case when execution.side = 'BUY' then execution.gross_amount else 0 end),
                       sum(case when execution.side = 'SELL' then execution.gross_amount else 0 end),
                       sum(case when execution.side = 'BUY' then execution.net_amount else 0 end),
                       sum(case when execution.side = 'SELL' then execution.net_amount else 0 end),
                       sum(execution.fee_amount),
                       sum(execution.tax_amount),
                       sum(coalesce(execution.realized_profit, 0)),
                       max(execution.executed_at),
                       ?
                  from stock_execution execution
                 where execution.executed_at >= ?
                   and execution.executed_at < ?
                 group by execution.account_id
                """,
                businessDate,
                updatedAt,
                rangeStart,
                rangeEnd
        );
    }

    public long countHoldingSnapshots(long closeCycleId) {
        Long count = jdbcClient.sql(
                        "select count(*) from stock_holding_snapshot where close_cycle_id = ?"
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public long findLastHoldingSnapshotAccountId(long closeCycleId) {
        Long accountId = jdbcClient.sql(
                        """
                        select coalesce(max(account_id), 0)
                          from stock_holding_snapshot
                         where close_cycle_id = ?
                        """
                )
                .param(closeCycleId)
                .query(Long.class)
                .single();
        return accountId == null ? 0L : accountId;
    }

    public List<Long> findHoldingSnapshotAccountCandidates(String symbol, long afterAccountId, int limit) {
        String symbolPredicate = symbol == null ? "" : "and symbol = :symbol";
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                        """
                        select account_id
                          from stock_holding
                         where account_id > :afterAccountId
                           and (quantity > 0 or reserved_quantity > 0)
                           %s
                         group by account_id
                         order by account_id asc
                         limit :limit
                        """.formatted(symbolPredicate)
                )
                .param("afterAccountId", afterAccountId)
                .param("limit", Math.max(1, limit));
        if (symbol != null) {
            statement = statement.param("symbol", symbol);
        }
        return statement.query(Long.class).list();
    }

    /**
     * Writes all positions for a bounded account cohort in one set-based statement. Account IDs
     * are the durable keyset checkpoint already present in the snapshot, avoiding a new source
     * table column or one JDBC round trip per holding.
     */
    public int snapshotHoldingsForAccounts(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDateTime snapshotAt,
            List<Long> accountIds
    ) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        String symbolPredicate = symbol == null ? "" : "and holding.symbol = :symbol";
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                        """
                        insert into stock_holding_snapshot(
                            close_cycle_id, close_run_id, account_id, symbol, quantity,
                            reserved_quantity, average_price, evaluation_price, snapshot_at
                        )
                        select :closeCycleId, :closeRunId, holding.account_id, holding.symbol,
                               holding.quantity, holding.reserved_quantity, holding.average_price,
                               coalesce(price.current_price, holding.average_price), :snapshotAt
                          from stock_holding holding
                          left join stock_price price on price.symbol = holding.symbol
                         where holding.account_id in (:accountIds)
                           and (holding.quantity > 0 or holding.reserved_quantity > 0)
                           %s
                         order by holding.account_id asc, holding.symbol asc
                        """.formatted(symbolPredicate)
                )
                .param("closeCycleId", closeCycleId)
                .param("closeRunId", closeRunId)
                .param("snapshotAt", snapshotAt)
                .param("accountIds", accountIds);
        if (symbol != null) {
            statement = statement.param("symbol", symbol);
        }
        return statement.update();
    }

    public int snapshotOrderBookDailySymbols(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDate simulationTradeDate,
            LocalDateTime snapshotAt,
            LocalDateTime executionRangeStart,
            LocalDateTime executionRangeEnd
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for bounded symbol report aggregation");
        }
        String sql = """
                insert into stock_order_book_daily_snapshot(
                    close_run_id, symbol, simulation_trade_date, snapshot_at, created_at,
                    name, market, enabled, market_enabled, market_status,
                    issued_shares, tradable_shares, initial_price, tick_size, price_limit_rate,
                    close_price, previous_close, change_rate,
                    price_time, price_provider,
                    execution_count, execution_quantity, turnover_amount,
                    open_price, high_price, low_price, last_execution_price,
                    buy_quantity, sell_quantity, buy_net_amount, sell_net_amount,
                    open_order_count, open_buy_order_count, open_sell_order_count, reserved_buy_cash,
                    holder_count, holding_quantity, pending_corporate_action_count,
                    first_executed_at, last_executed_at
                )
                select :closeRunId,
                       i.symbol,
                       :simulationTradeDate,
                       :snapshotAt,
                       :snapshotAt,
                       i.name,
                       i.market,
                       i.enabled,
                       coalesce(m.enabled, false),
                       coalesce(m.market_status, 'CLOSED'),
                       i.issued_shares,
                       i.tradable_shares,
                       i.initial_price,
                       i.tick_size,
                       i.price_limit_rate,
                       coalesce(p.close_price, i.initial_price),
                       coalesce(p.previous_close, i.initial_price),
                       case
                         when coalesce(p.previous_close, i.initial_price) > 0
                         then round(
                             (coalesce(p.close_price, i.initial_price) - coalesce(p.previous_close, i.initial_price))
                             * 100 / coalesce(p.previous_close, i.initial_price),
                             4
                         )
                         else 0
                       end,
                       p.price_time,
                       p.price_provider,
                       coalesce(e.execution_count, 0),
                       coalesce(e.execution_quantity, 0),
                       coalesce(e.turnover_amount, 0),
                       coalesce(e.open_price, 0),
                       coalesce(e.high_price, 0),
                       coalesce(e.low_price, 0),
                       coalesce(e.last_execution_price, 0),
                       coalesce(e.buy_quantity, 0),
                       coalesce(e.sell_quantity, 0),
                       coalesce(e.buy_net_amount, 0),
                       coalesce(e.sell_net_amount, 0),
                       coalesce(o.pre_cancel_open_order_count, 0),
                       coalesce(o.pre_cancel_buy_order_count, 0),
                       coalesce(o.pre_cancel_sell_order_count, 0),
                       coalesce(o.pre_cancel_reserved_buy_cash, 0),
                       coalesce(h.holder_count, 0),
                       coalesce(h.holding_quantity, 0),
                       coalesce(c.pending_corporate_action_count, 0),
                       e.first_executed_at,
                       e.last_executed_at
                  from stock_order_book_instrument i
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join stock_close_price_snapshot p
                    on p.close_cycle_id = :closeCycleId
                   and p.symbol = i.symbol
                  left join (
                       select execution.symbol,
                              sum(case when execution.side = 'BUY' then 1 else 0 end) as execution_count,
                              sum(case when execution.side = 'BUY' then execution.quantity else 0 end) as execution_quantity,
                              sum(case when execution.side = 'BUY' then execution.gross_amount else 0 end) as turnover_amount,
                              (
                                  select opening_execution.price
                                    from %s opening_execution
                                   where opening_execution.source = 'INTERNAL_ORDER_BOOK'
                                     and opening_execution.symbol = :symbol
                                     and opening_execution.side = 'BUY'
                                     and opening_execution.executed_at >= :executionRangeStart
                                     and opening_execution.executed_at < :executionRangeEnd
                                   order by opening_execution.executed_at asc, opening_execution.id asc
                                   limit 1
                              ) as open_price,
                              max(case when execution.side = 'BUY' then execution.price end) as high_price,
                              min(case when execution.side = 'BUY' then execution.price end) as low_price,
                              (
                                  select closing_execution.price
                                    from %s closing_execution
                                   where closing_execution.source = 'INTERNAL_ORDER_BOOK'
                                     and closing_execution.symbol = :symbol
                                     and closing_execution.side = 'BUY'
                                     and closing_execution.executed_at >= :executionRangeStart
                                     and closing_execution.executed_at < :executionRangeEnd
                                   order by closing_execution.executed_at desc, closing_execution.id desc
                                   limit 1
                              ) as last_execution_price,
                              sum(case when execution.side = 'BUY' then execution.quantity else 0 end) as buy_quantity,
                              sum(case when execution.side = 'SELL' then execution.quantity else 0 end) as sell_quantity,
                              sum(case when execution.side = 'BUY' then execution.net_amount else 0 end) as buy_net_amount,
                              sum(case when execution.side = 'SELL' then execution.net_amount else 0 end) as sell_net_amount,
                              min(execution.executed_at) as first_executed_at,
                              max(execution.executed_at) as last_executed_at
                         from %s execution
                        where execution.source = 'INTERNAL_ORDER_BOOK'
                          and execution.symbol = :symbol
                          and execution.executed_at >= :executionRangeStart
                          and execution.executed_at < :executionRangeEnd
                        group by execution.symbol
                  ) e on e.symbol = i.symbol
                  left join stock_close_open_order_summary o
                    on o.close_cycle_id = :closeCycleId
                   and o.symbol = i.symbol
                  left join (
                       select h.symbol,
                              count(distinct h.account_id) as holder_count,
                              sum(h.quantity) as holding_quantity
                         from stock_holding_snapshot h
                         join stock_close_account_snapshot a
                          on a.close_cycle_id = h.close_cycle_id
                          and a.account_id = h.account_id
                          and a.account_status = 'ACTIVE'
                        where h.close_cycle_id = :closeCycleId
                          and h.symbol = :symbol
                        group by h.symbol
                  ) h on h.symbol = i.symbol
                  left join (
                       select symbol,
                              count(*) as pending_corporate_action_count
                         from stock_corporate_action
                        where status in ('ANNOUNCED', 'EX_RIGHTS_APPLIED')
                          and symbol = :symbol
                        group by symbol
                  ) c on c.symbol = i.symbol
                 where i.symbol = :symbol
                """.formatted(
                executionCandleTable,
                executionCandleTable,
                executionRangeTable
        );
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("closeRunId", closeRunId)
                .param("simulationTradeDate", simulationTradeDate)
                .param("snapshotAt", snapshotAt)
                .param("closeCycleId", closeCycleId)
                .param("executionRangeStart", executionRangeStart)
                .param("executionRangeEnd", executionRangeEnd)
                .param("symbol", symbol);
        return statement.update();
    }

    public int deleteOrderBookDailySnapshot(long closeRunId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for bounded symbol report replacement");
        }
        return jdbcTemplate.update(
                """
                delete from stock_order_book_daily_snapshot
                 where close_run_id = ?
                   and symbol = ?
                """,
                closeRunId,
                symbol
        );
    }

    public List<String> findCloseReportSymbolChunk(
            long closeCycleId,
            String afterSymbol,
            int limit
    ) {
        return jdbcTemplate.queryForList(
                """
                select symbol
                  from stock_close_price_snapshot
                 where close_cycle_id = ?
                   and order_book_symbol = true
                   and symbol > ?
                 order by symbol asc
                 limit ?
                """,
                String.class,
                closeCycleId,
                afterSymbol,
                limit
        );
    }

    public int updateClosePriceLastExecutionId(
            long closeCycleId,
            String symbol,
            LocalDateTime executionRangeStart,
            LocalDateTime executionRangeEnd
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for bounded execution watermark update");
        }
        return jdbcTemplate.update(
                """
                update stock_close_price_snapshot
                   set last_execution_id = (
                       select max(execution.id)
                         from %s execution
                        where execution.source = 'INTERNAL_ORDER_BOOK'
                          and execution.symbol = ?
                          and execution.executed_at >= ?
                          and execution.executed_at < ?
                   )
                 where close_cycle_id = ?
                   and symbol = ?
                """.formatted(executionRangeTable),
                symbol,
                executionRangeStart,
                executionRangeEnd,
                closeCycleId,
                symbol
        );
    }

    public void completeCloseRun(
            long closeRunId,
            int cancelledOrderCount,
            int holdingSnapshotCount,
            int priceRolloverCount,
            LocalDateTime completedAt
    ) {
        jdbcTemplate.update(
                """
                update stock_market_close_run
                   set status = 'COMPLETED',
                       cancelled_order_count = ?,
                       holding_snapshot_count = ?,
                       price_rollover_count = ?,
                       completed_at = ?
                 where id = ?
                """,
                cancelledOrderCount,
                holdingSnapshotCount,
                priceRolloverCount,
                completedAt,
                closeRunId
        );
    }

    private record LockedOrderRow(
            long id,
            long accountId,
            String symbol,
            String side,
            String status,
            long remainingQuantity,
            BigDecimal reservedCash
    ) {

        private boolean isOpen() {
            return remainingQuantity > 0
                    && ("PENDING".equals(status) || "PARTIALLY_FILLED".equals(status));
        }

        private MarketCloseOrderRow toMarketCloseOrderRow() {
            return new MarketCloseOrderRow(
                    id,
                    accountId,
                    symbol,
                    side,
                    remainingQuantity,
                    reservedCash
            );
        }
    }

    private boolean isMySql(JdbcTemplate template) {
        String productName = template.execute(
                (ConnectionCallback<String>) this::databaseProductName
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    private String databaseProductName(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName();
    }

    private record HoldingLockKey(long accountId, String symbol) {
    }

    public record HoldingReservationKey(long accountId, String symbol) {
    }

    public record ReleasedReservationTotals(BigDecimal buyCash, long sellQuantity) {
    }
}
