package stock.batch.service.execution.biz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionAccountDaySummaryAccumulatorTest {

    @Test
    void flush_aggregatesCommittedExecutionDeltasByAccountAndDay() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("drop table if exists stock_execution_account_day_summary");
        jdbcTemplate.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    execution_count bigint not null,
                    buy_quantity bigint not null,
                    sell_quantity bigint not null,
                    gross_amount decimal(19, 2) not null,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );
        LocalDateTime firstExecution = LocalDateTime.of(2026, 7, 14, 9, 0);
        LocalDateTime secondExecution = firstExecution.plusMinutes(1);

        accumulator.recordBuy(11L, 30L, new BigDecimal("150000.00"), firstExecution);
        accumulator.recordBuy(11L, 20L, new BigDecimal("101000.00"), secondExecution);
        accumulator.recordSell(12L, 50L, new BigDecimal("251000.00"), secondExecution);
        accumulator.flush();

        assertThat(jdbcTemplate.queryForList("""
                select account_id, execution_count, buy_quantity, sell_quantity, gross_amount
                  from stock_execution_account_day_summary
                 order by account_id
                """))
                .extracting(
                        row -> ((Number) row.get("account_id")).longValue(),
                        row -> ((Number) row.get("execution_count")).longValue(),
                        row -> ((Number) row.get("buy_quantity")).longValue(),
                        row -> ((Number) row.get("sell_quantity")).longValue(),
                        row -> row.get("gross_amount")
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(11L, 2L, 50L, 0L, new BigDecimal("251000.00")),
                        org.assertj.core.groups.Tuple.tuple(12L, 1L, 0L, 50L, new BigDecimal("251000.00"))
                );
    }

    @Test
    void flushOnShutdown_persistsPendingExecutionDeltas() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_shutdown;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null,
                    execution_count bigint not null,
                    buy_quantity bigint not null,
                    sell_quantity bigint not null,
                    gross_amount decimal(19, 2) not null,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );

        accumulator.recordBuy(
                21L,
                7L,
                new BigDecimal("35000.00"),
                LocalDateTime.of(2026, 7, 14, 9, 30)
        );
        accumulator.flushOnShutdown();

        assertThat(jdbcTemplate.queryForObject(
                "select execution_count from stock_execution_account_day_summary where account_id = 21",
                Long.class
        )).isEqualTo(1L);
    }
}
