package stock.batch.service.marketclose.biz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.batch.service.simulation.SimulationMarketSessionService;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;
import web.common.core.simulation.SimulationMarketSession;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBookMarketSessionStateServiceTest {

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("market_session_state_test"));
    private final SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
    private final PostCloseCycleService postCloseCycleService = mock(PostCloseCycleService.class);
    private final MarketSessionFenceService marketSessionFenceService = new MarketSessionFenceService(
            jdbcTemplate,
            simulationMarketSessionService,
            new SimpleMeterRegistry(),
            30
    );
    private final OrderBookMarketSessionStateService service = new OrderBookMarketSessionStateService(
            simulationMarketSessionService,
            marketSessionFenceService,
            postCloseCycleService
    );

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("drop table if exists stock_order_book_market_config");
        jdbcTemplate.execute("drop table if exists stock_virtual_market_config");
        jdbcTemplate.execute("drop table if exists stock_market_session_fence");
        jdbcTemplate.execute("drop table if exists stock_market_business_state");
        jdbcTemplate.execute("drop table if exists stock_simulation_clock");
        jdbcTemplate.execute(
                """
                create table stock_order_book_market_config (
                  symbol varchar(20) not null primary key,
                  enabled boolean not null,
                  market_status varchar(20) not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_virtual_market_config (
                  symbol varchar(20) not null primary key,
                  enabled boolean not null,
                  market_status varchar(20) not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_business_state (
                  state_id varchar(20) not null primary key,
                  active_business_date date not null,
                  preparing_business_date date,
                  raw_simulation_date date not null,
                  version bigint not null,
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_session_fence (
                  market_type varchar(20) not null,
                  symbol varchar(20) not null,
                  business_date date not null,
                  session_epoch bigint not null,
                  session_state varchar(20) not null,
                  state_changed_at timestamp not null,
                  version bigint not null,
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  primary key (market_type, symbol)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_simulation_clock (
                  clock_id varchar(40) not null primary key,
                  base_simulation_date date not null,
                  real_seconds_per_simulation_day int not null,
                  accumulated_real_seconds bigint not null,
                  running boolean not null,
                  last_started_at timestamp,
                  last_heartbeat_at timestamp,
                  timezone varchar(50) not null,
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
        insertMarketConfig("OPENED", true, "OPEN");
        insertMarketConfig("CLOSED", true, "CLOSED");
        insertMarketConfig("HALTED", true, "HALTED");
        insertMarketConfig("DISABLED", false, "OPEN");
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 2, 18, 0));
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 2));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 2));
        when(simulationMarketSessionService.openTime())
                .thenReturn(LocalTime.of(6, 0));
        when(postCloseCycleService.isReadyToOpen(java.time.LocalDate.of(2026, 7, 2)))
                .thenReturn(true);
    }

    @Test
    void lockOpenOrderBookFences_multipleSymbols_returnsOneConsistentApproval() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 3, 6, 0);
        insertMarketConfig("FENCE_A", true, "OPEN");
        insertMarketConfig("FENCE_B", true, "OPEN");
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date,
                    version, created_at, updated_at
                ) values ('DEFAULT', date '2026-07-03', null, date '2026-07-03', 0, ?, ?)
                """,
                recordedAt,
                recordedAt
        );
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at,
                    timezone, created_at, updated_at
                ) values ('DEFAULT', date '2026-07-03', 7200, 1800, false, null, null,
                          'Asia/Seoul', ?, ?)
                """,
                recordedAt,
                recordedAt
        );
        insertFence("FENCE_A", 11L, recordedAt);
        insertFence("FENCE_B", 12L, recordedAt);

        var approval = marketSessionFenceService.lockOpenOrderBookFences(
                List.of("FENCE_B", "FENCE_A", "FENCE_B")
        );

        assertThat(approval).isPresent();
        assertThat(approval.orElseThrow().sessionEpochs())
                .containsEntry("FENCE_A", 11L)
                .containsEntry("FENCE_B", 12L)
                .hasSize(2);
    }

    @Test
    void lockOpenOrderBookFences_preparingDateStillSet_failsClosed() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 3, 6, 0);
        insertBusinessState(recordedAt);
        jdbcTemplate.update(
                "update stock_market_business_state set preparing_business_date = date '2026-07-04'"
        );
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at,
                    timezone, created_at, updated_at
                ) values ('DEFAULT', date '2026-07-03', 7200, 1800, false, null, null,
                          'Asia/Seoul', ?, ?)
                """,
                recordedAt,
                recordedAt
        );
        insertFence("OPENED", 11L, recordedAt);

        assertThat(marketSessionFenceService.lockOpenOrderBookFences(List.of("OPENED"))).isEmpty();
    }

    @Test
    void hasOpenOrderBookMarket_matchingBusinessDateAndOpenFence_returnsTrue() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 3, 6, 0);
        insertBusinessState(recordedAt);
        insertFence("OPENED", 11L, recordedAt);

        assertThat(marketSessionFenceService.hasOpenOrderBookMarket()).isTrue();
    }

    @Test
    void hasOpenOrderBookMarket_staleBusinessDateFence_returnsFalse() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 3, 6, 0);
        insertBusinessState(recordedAt);
        insertFence("OPENED", 11L, recordedAt);
        jdbcTemplate.update(
                "update stock_market_session_fence set business_date = date '2026-07-02' where symbol = 'OPENED'"
        );

        assertThat(marketSessionFenceService.hasOpenOrderBookMarket()).isFalse();
    }

    @Test
    void hasOpenMarket_configClosedButEnabledFenceOpen_returnsTrue() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 3, 6, 0);
        updateMarketStatus("OPENED", "CLOSED", recordedAt);
        insertFence("OPENED", 11L, recordedAt);

        assertThat(marketSessionFenceService.hasOpenMarket()).isTrue();
    }

    @Test
    void beginClose_recoveredOlderBusinessDate_doesNotRegressRawSimulationDate() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 2, 18, 0);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date,
                    version, created_at, updated_at
                ) values ('DEFAULT', date '2026-07-02', date '2026-07-03', date '2026-07-03', 0, ?, ?)
                """,
                recordedAt,
                recordedAt
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state,
                    state_changed_at, version, created_at, updated_at
                ) values ('ORDER_BOOK', 'OPENED', date '2026-07-02', 10, 'OPEN', ?, 0, ?, ?)
                """,
                recordedAt,
                recordedAt,
                recordedAt
        );

        marketSessionFenceService.beginClose(
                java.time.LocalDate.of(2026, 7, 2),
                recordedAt,
                null
        );

        assertThat(jdbcTemplate.queryForObject(
                "select raw_simulation_date from stock_market_business_state where state_id = 'DEFAULT'",
                java.time.LocalDate.class
        )).isEqualTo(java.time.LocalDate.of(2026, 7, 3));
    }

    @Test
    void isRegularSessionOpen_missingBusinessState_failsClosed() {
        assertThat(marketSessionFenceService.isRegularSessionOpen(java.time.LocalDate.of(2026, 7, 3)))
                .isFalse();
    }

    @Test
    void syncCurrentSession_afterClose_closesOnlyEnabledOpenMarkets() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.AFTER_CLOSE);

        int updated = service.syncCurrentSession();

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("CLOSED");
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
        assertThat(statusOf("HALTED")).isEqualTo("HALTED");
        assertThat(statusOf("DISABLED")).isEqualTo("OPEN");
    }

    @Test
    void syncCurrentSession_afterClose_publishesCycleBeforeClosingOrderEntry() {
        SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        PostCloseCycleService cycleService = mock(PostCloseCycleService.class);
        OrderBookMarketSessionStateService stateService = new OrderBookMarketSessionStateService(
                sessionService,
                fenceService,
                cycleService
        );
        LocalDateTime closeAt = LocalDateTime.of(2026, 7, 3, 18, 0);
        when(sessionService.currentSimulationDateTime()).thenReturn(closeAt);
        when(sessionService.sessionAt(closeAt)).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(fenceService.isAfterCloseSynchronized(closeAt.toLocalDate(), closeAt.toLocalDate()))
                .thenReturn(false);

        stateService.syncCurrentSession();

        var ordered = inOrder(cycleService, fenceService);
        ordered.verify(cycleService).ensureFullMarketCycle(eq(closeAt.toLocalDate()), any(LocalDateTime.class));
        ordered.verify(fenceService).beginClose(
                closeAt.toLocalDate(),
                closeAt.toLocalDate(),
                closeAt,
                null
        );
    }

    @Test
    void syncCurrentSession_regular_opensOnlyEnabledClosedMarkets() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 6, 0));

        int updated = service.syncCurrentSession();

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("OPEN");
        assertThat(statusOf("CLOSED")).isEqualTo("OPEN");
        assertThat(statusOf("HALTED")).isEqualTo("HALTED");
        assertThat(statusOf("DISABLED")).isEqualTo("OPEN");
    }

    @Test
    void syncCurrentSession_regularAlreadySynchronized_doesNotRewriteOrExclusivelyRelockFences() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 6, 0));
        service.syncCurrentSession();
        long businessVersion = businessStateVersion();
        long fenceVersion = totalFenceVersion();

        int updated = service.syncCurrentSession();

        assertThat(List.of(updated, businessStateVersion(), totalFenceVersion()))
                .containsExactly(0, businessVersion, fenceVersion);
    }

    @Test
    void syncCurrentSession_afterCloseAlreadySynchronized_doesNotRewriteFences() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.AFTER_CLOSE);
        service.syncCurrentSession();
        long businessVersion = businessStateVersion();
        long fenceVersion = totalFenceVersion();

        int updated = service.syncCurrentSession();

        assertThat(List.of(updated, businessStateVersion(), totalFenceVersion()))
                .containsExactly(0, businessVersion, fenceVersion);
        verify(postCloseCycleService, times(1)).ensureFullMarketCycle(
                eq(java.time.LocalDate.of(2026, 7, 2)),
                any(LocalDateTime.class)
        );
    }

    @Test
    void syncCurrentSession_afterCloseRawDateAhead_closesActiveBusinessDateOnly() {
        LocalDateTime rawNow = LocalDateTime.of(2026, 7, 4, 18, 0);
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 2, 18, 0);
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(rawNow);
        when(simulationMarketSessionService.currentSimulationDate()).thenReturn(rawNow.toLocalDate());
        when(simulationMarketSessionService.sessionAt(rawNow)).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date,
                    version, created_at, updated_at
                ) values ('DEFAULT', date '2026-07-02', date '2026-07-03', date '2026-07-03', 0, ?, ?)
                """,
                recordedAt,
                recordedAt
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state,
                    state_changed_at, version, created_at, updated_at
                ) values ('ORDER_BOOK', 'OPENED', date '2026-07-02', 10, 'OPEN', ?, 0, ?, ?)
                """,
                recordedAt,
                recordedAt,
                recordedAt
        );

        service.syncCurrentSession();

        verify(postCloseCycleService).ensureFullMarketCycle(
                eq(java.time.LocalDate.of(2026, 7, 2)),
                any(LocalDateTime.class)
        );
        verify(postCloseCycleService, never()).ensureFullMarketCycle(
                eq(java.time.LocalDate.of(2026, 7, 4)),
                any(LocalDateTime.class)
        );
        assertThat(jdbcTemplate.queryForObject(
                "select active_business_date from stock_market_business_state where state_id = 'DEFAULT'",
                java.time.LocalDate.class
        )).isEqualTo(java.time.LocalDate.of(2026, 7, 2));
        assertThat(jdbcTemplate.queryForObject(
                "select raw_simulation_date from stock_market_business_state where state_id = 'DEFAULT'",
                java.time.LocalDate.class
        )).isEqualTo(java.time.LocalDate.of(2026, 7, 4));
    }

    @Test
    void syncCurrentSession_regular_blocksOpenWhenPreviousCloseIsMissing() {
        // given
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 6, 0));
        when(postCloseCycleService.isReadyToOpen(java.time.LocalDate.of(2026, 7, 2)))
                .thenReturn(false);

        // when
        int updated = service.syncCurrentSession();

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("CLOSED");
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
        verify(postCloseCycleService).isReadyToOpen(java.time.LocalDate.of(2026, 7, 2));
    }

    @Test
    void syncCurrentSession_regular_firstSimulationDayDoesNotRequirePreviousClose() {
        // given
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 6, 0));

        // when
        int updated = service.syncCurrentSession();

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("CLOSED")).isEqualTo("OPEN");
        verify(postCloseCycleService, never()).isReadyToOpen(java.time.LocalDate.of(2026, 7, 2));
    }

    @Test
    void syncCurrentSession_regular_reopensCircuitBreakerFromPreviousSimulationDay() {
        insertMarketConfig("CIRCUIT", true, "CIRCUIT_BREAKER");
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 6, 0));

        int updated = service.syncCurrentSession();

        assertThat(updated).isEqualTo(2);
        assertThat(statusOf("CLOSED")).isEqualTo("OPEN");
        assertThat(statusOf("CIRCUIT")).isEqualTo("OPEN");
        assertThat(statusOf("HALTED")).isEqualTo("HALTED");
    }

    @Test
    void syncCurrentSession_regular_doesNotReopenManuallyClosedMarketAfterOpen() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 10, 0));
        updateMarketStatus("CLOSED", "CLOSED", LocalDateTime.of(2026, 7, 3, 9, 30));

        int updated = service.syncCurrentSession();

        assertThat(updated).isZero();
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
    }

    @Test
    void syncCurrentSession_regular_doesNotReopenCircuitBreakerCreatedAfterOpenBoundary() {
        insertMarketConfig("CIRCUIT", true, "CIRCUIT_BREAKER");
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 10, 0));
        updateMarketStatus("CIRCUIT", "CIRCUIT_BREAKER", LocalDateTime.of(2026, 7, 3, 9, 30));
        updateMarketStatus("CLOSED", "CLOSED", LocalDateTime.of(2026, 7, 3, 9, 30));

        int updated = service.syncCurrentSession();

        assertThat(updated).isZero();
        assertThat(statusOf("CIRCUIT")).isEqualTo("CIRCUIT_BREAKER");
    }

    @Test
    void syncCurrentSession_preOpen_closesEnabledOpenMarkets() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.PRE_OPEN);

        int updated = service.syncCurrentSession();

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("CLOSED");
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
        assertThat(statusOf("HALTED")).isEqualTo("HALTED");
        assertThat(statusOf("DISABLED")).isEqualTo("OPEN");
        assertThat(fenceStateOf("OPENED")).isEqualTo("PREPARING");
        assertThat(fenceStateOf("CLOSED")).isEqualTo("PREPARING");
        assertThat(fenceStateOf("HALTED")).isEqualTo("PREPARING");
        assertThat(fenceStateOf("DISABLED")).isEqualTo("CLOSED");
        assertThat(fenceBusinessDateOf("OPENED")).isEqualTo(java.time.LocalDate.of(2026, 7, 3));
    }

    @Test
    void syncCurrentSession_preOpenAlreadySynchronized_doesNotRewriteFences() {
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        service.syncCurrentSession();
        long businessVersion = businessStateVersion();
        long fenceVersion = totalFenceVersion();

        int updated = service.syncCurrentSession();

        assertThat(List.of(updated, businessStateVersion(), totalFenceVersion()))
                .containsExactly(0, businessVersion, fenceVersion);
    }

    private void insertMarketConfig(String symbol, boolean enabled, String marketStatus) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                values (?, ?, ?, ?)
                """,
                symbol,
                enabled,
                marketStatus,
                LocalDateTime.of(2026, 7, 2, 12, 0)
        );
    }

    private String statusOf(String symbol) {
        return jdbcTemplate.queryForObject(
                "select market_status from stock_order_book_market_config where symbol = ?",
                String.class,
                symbol
        );
    }

    private String fenceStateOf(String symbol) {
        return jdbcTemplate.queryForObject(
                "select session_state from stock_market_session_fence where market_type = 'ORDER_BOOK' and symbol = ?",
                String.class,
                symbol
        );
    }

    private java.time.LocalDate fenceBusinessDateOf(String symbol) {
        return jdbcTemplate.queryForObject(
                "select business_date from stock_market_session_fence where market_type = 'ORDER_BOOK' and symbol = ?",
                java.time.LocalDate.class,
                symbol
        );
    }

    private void updateMarketStatus(String symbol, String marketStatus, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                "update stock_order_book_market_config set market_status = ?, updated_at = ? where symbol = ?",
                marketStatus,
                updatedAt,
                symbol
        );
    }

    private void insertFence(String symbol, long sessionEpoch, LocalDateTime recordedAt) {
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state,
                    state_changed_at, version, created_at, updated_at
                ) values ('ORDER_BOOK', ?, date '2026-07-03', ?, 'OPEN', ?, 0, ?, ?)
                """,
                symbol,
                sessionEpoch,
                recordedAt,
                recordedAt,
                recordedAt
        );
    }

    private void insertBusinessState(LocalDateTime recordedAt) {
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date,
                    version, created_at, updated_at
                ) values ('DEFAULT', date '2026-07-03', null, date '2026-07-03', 0, ?, ?)
                """,
                recordedAt,
                recordedAt
        );
    }

    private long businessStateVersion() {
        Long version = jdbcTemplate.queryForObject(
                "select version from stock_market_business_state where state_id = 'DEFAULT'",
                Long.class
        );
        return version == null ? 0L : version;
    }

    private long totalFenceVersion() {
        Long version = jdbcTemplate.queryForObject(
                "select coalesce(sum(version), 0) from stock_market_session_fence",
                Long.class
        );
        return version == null ? 0L : version;
    }

}
