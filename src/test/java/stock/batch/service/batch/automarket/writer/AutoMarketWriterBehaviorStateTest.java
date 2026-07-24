package stock.batch.service.batch.automarket.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;

class AutoMarketWriterBehaviorStateTest {

    private JdbcTemplate jdbcTemplate;
    private AutoMarketWriter writer;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auto_market_behavior_state_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table stock_auto_participant_position_state (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    position_opened_business_date date not null,
                    holding_trading_days int not null,
                    average_down_rounds int not null,
                    last_average_down_business_date date,
                    peak_close_price decimal(19,2) not null,
                    last_seen_business_date date not null,
                    updated_at timestamp not null,
                    primary key(account_id, symbol),
                    constraint chk_holding_trading_days check (holding_trading_days > 0)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    average_price decimal(19,2) not null,
                    primary key(account_id, symbol)
                )
                """);
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_position_state
                values (1, 'STOCK001', date '2027-01-10', 5, 1, null, 110, date '2027-01-17', current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_position_state
                values (2, 'STOCK002', date '2027-01-10', 5, 0, null, 210, date '2027-01-17', current_timestamp)
                """
        );
        writer = new AutoMarketWriter(
                jdbcTemplate,
                mock(OrderBookReadySymbolQueue.class),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void markAverageDownDecisions_distinctPositions_updatesOneCooldownPerPosition() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        LocalDateTime updatedAt = businessDate.atTime(9, 30);

        int updated = writer.markAverageDownDecisions(
                List.of(
                        new AutoMarketWriter.PositionStateKey(2L, "STOCK002"),
                        new AutoMarketWriter.PositionStateKey(1L, "STOCK001"),
                        new AutoMarketWriter.PositionStateKey(1L, "STOCK001")
                ),
                businessDate,
                updatedAt
        );

        assertThat(List.of(updated, markedPositionCount(businessDate, updatedAt)))
                .containsExactly(2, 2);
    }

    @Test
    void markAverageDownDecisions_missingPositionState_initializesFromHoldingInSameTransaction() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        LocalDateTime updatedAt = businessDate.atTime(10, 15);
        jdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, average_price) values (3, 'STOCK003', 315.50)"
        );

        int marked = writer.markAverageDownDecisions(
                List.of(new AutoMarketWriter.PositionStateKey(3L, "STOCK003")),
                businessDate,
                updatedAt
        );

        Map<String, Object> state = jdbcTemplate.queryForMap(
                "select * from stock_auto_participant_position_state where account_id = 3 and symbol = 'STOCK003'"
        );
        assertThat(List.of(
                marked,
                state.get("position_opened_business_date"),
                state.get("holding_trading_days"),
                state.get("average_down_rounds"),
                state.get("last_average_down_business_date"),
                state.get("peak_close_price"),
                state.get("last_seen_business_date")
        )).containsExactly(
                1,
                java.sql.Date.valueOf(businessDate),
                1,
                0,
                java.sql.Date.valueOf(businessDate),
                new java.math.BigDecimal("315.50"),
                java.sql.Date.valueOf(businessDate)
        );
    }

    private int markedPositionCount(LocalDate businessDate, LocalDateTime updatedAt) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_auto_participant_position_state
                 where last_average_down_business_date = ?
                   and updated_at = ?
                """,
                Integer.class,
                businessDate,
                updatedAt
        );
        return count == null ? 0 : count;
    }
}
