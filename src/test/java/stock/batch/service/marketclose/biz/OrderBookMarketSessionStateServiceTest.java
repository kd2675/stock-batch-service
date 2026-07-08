package stock.batch.service.marketclose.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import stock.batch.service.simulation.SimulationMarketSessionService;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;
import web.common.core.simulation.SimulationMarketSession;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBookMarketSessionStateServiceTest {

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("market_session_state_test"));
    private final SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
    private final MarketClosePostProcessingCompletionService postProcessingCompletionService =
            mock(MarketClosePostProcessingCompletionService.class);
    private final OrderBookMarketSessionStateService service = new OrderBookMarketSessionStateService(
            JdbcClient.create(jdbcTemplate),
            simulationMarketSessionService,
            postProcessingCompletionService
    );

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("drop table if exists stock_order_book_market_config");
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
        insertMarketConfig("OPENED", true, "OPEN");
        insertMarketConfig("CLOSED", true, "CLOSED");
        insertMarketConfig("HALTED", true, "HALTED");
        insertMarketConfig("DISABLED", false, "OPEN");
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 2, 18, 0));
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 2));
        when(simulationMarketSessionService.openTime())
                .thenReturn(LocalTime.of(6, 0));
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 2)))
                .thenReturn(true);
    }

    @Test
    void syncCurrentSession_afterClose_closesOnlyEnabledOpenMarkets() {
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);

        int updated = service.syncCurrentSession();

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("CLOSED");
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
        assertThat(statusOf("HALTED")).isEqualTo("HALTED");
        assertThat(statusOf("DISABLED")).isEqualTo("OPEN");
    }

    @Test
    void syncCurrentSession_regular_opensOnlyEnabledClosedMarkets() {
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
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
    void syncCurrentSession_regular_blocksOpenWhenPreviousCloseIsMissing() {
        // given
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 6, 0));
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 2)))
                .thenReturn(false);

        // when
        int updated = service.syncCurrentSession();

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("CLOSED");
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
        verify(postProcessingCompletionService).isComplete(java.time.LocalDate.of(2026, 7, 2));
    }

    @Test
    void syncCurrentSession_regular_firstSimulationDayDoesNotRequirePreviousClose() {
        // given
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
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
        verify(postProcessingCompletionService, never()).isComplete(java.time.LocalDate.of(2026, 7, 2));
    }

    @Test
    void syncCurrentSession_regular_reopensCircuitBreakerFromPreviousSimulationDay() {
        insertMarketConfig("CIRCUIT", true, "CIRCUIT_BREAKER");
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
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
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
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
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
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
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);

        int updated = service.syncCurrentSession();

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("OPENED")).isEqualTo("CLOSED");
        assertThat(statusOf("CLOSED")).isEqualTo("CLOSED");
        assertThat(statusOf("HALTED")).isEqualTo("HALTED");
        assertThat(statusOf("DISABLED")).isEqualTo("OPEN");
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

    private void updateMarketStatus(String symbol, String marketStatus, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                "update stock_order_book_market_config set market_status = ?, updated_at = ? where symbol = ?",
                marketStatus,
                updatedAt,
                symbol
        );
    }

}
