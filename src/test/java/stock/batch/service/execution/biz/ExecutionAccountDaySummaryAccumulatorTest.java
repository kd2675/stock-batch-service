package stock.batch.service.execution.biz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionAccountDaySummaryAccumulatorTest {

    @Test
    void constructor_pendingQueueAboveBound_throws() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_pending_limit;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));

        assertThatThrownBy(() -> new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry(),
                1_000_001,
                5_000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPendingDeltas must be between 1 and 1000000");
    }

    @Test
    void constructor_flushBatchAboveBound_throws() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_limit;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));

        assertThatThrownBy(() -> new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry(),
                10_000,
                5_001
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5000");
    }

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
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );
        LocalDateTime firstExecution = LocalDateTime.of(2026, 7, 14, 9, 0);
        LocalDateTime secondExecution = firstExecution.plusMinutes(1);

        accumulator.recordBuy(11L, 30L, amounts("150000.00", "150.00", "0.00", "150150.00", null), firstExecution);
        accumulator.recordBuy(11L, 20L, amounts("101000.00", "101.00", "0.00", "101101.00", null), secondExecution);
        accumulator.recordSell(12L, 50L, amounts("251000.00", "251.00", "502.00", "250247.00", "50000.00"), secondExecution);
        accumulator.flush();

        assertThat(jdbcTemplate.queryForList("""
                select account_id, execution_count, buy_quantity, sell_quantity, gross_amount,
                       buy_gross_amount, sell_gross_amount, buy_net_amount, sell_net_amount,
                       fee_amount, tax_amount, realized_profit
                  from stock_execution_account_day_summary
                 order by account_id
                """))
                .extracting(
                        row -> ((Number) row.get("account_id")).longValue(),
                        row -> ((Number) row.get("execution_count")).longValue(),
                        row -> ((Number) row.get("buy_quantity")).longValue(),
                        row -> ((Number) row.get("sell_quantity")).longValue(),
                        row -> row.get("gross_amount"),
                        row -> row.get("buy_gross_amount"),
                        row -> row.get("sell_gross_amount"),
                        row -> row.get("buy_net_amount"),
                        row -> row.get("sell_net_amount"),
                        row -> row.get("fee_amount"),
                        row -> row.get("tax_amount"),
                        row -> row.get("realized_profit")
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                11L, 2L, 50L, 0L,
                                new BigDecimal("251000.00"),
                                new BigDecimal("251000.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("251251.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("251.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("0.00")
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                12L, 1L, 0L, 50L,
                                new BigDecimal("251000.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("251000.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("250247.00"),
                                new BigDecimal("251.00"),
                                new BigDecimal("502.00"),
                                new BigDecimal("50000.00")
                        )
                );
    }

    @Test
    void record_highTradeVolumeForOneAccount_usesOneBoundedAccountDaySlot() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_high_volume;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
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
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                meterRegistry,
                1,
                1
        );
        LocalDateTime executedAt = LocalDateTime.of(2026, 7, 14, 10, 0);

        for (int index = 0; index < 10_000; index++) {
            accumulator.recordBuy(11L, 1L, buyAmounts("100.00"), executedAt.plusNanos(index));
        }
        accumulator.recordSell(12L, 1L, sellAmounts("100.00"), executedAt);
        accumulator.flush();

        assertThat(jdbcTemplate.queryForObject(
                "select execution_count from stock_execution_account_day_summary where account_id = 11",
                Long.class
        )).isEqualTo(10_000L);
        assertThat(meterRegistry.counter(
                "stock.execution.account.day.summary.deltas",
                "result",
                "overflow"
        ).count()).isEqualTo(1.0d);
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
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );

        accumulator.recordBuy(
                21L,
                7L,
                buyAmounts("35000.00"),
                LocalDateTime.of(2026, 7, 14, 9, 30)
        );
        accumulator.flushOnShutdown();

        assertThat(jdbcTemplate.queryForObject(
                "select execution_count from stock_execution_account_day_summary where account_id = 21",
                Long.class
        )).isEqualTo(1L);
    }

    @Test
    void flush_finalizedDailySnapshotExists_discardsLateInMemoryDelta() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_finalized;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
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
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        jdbcTemplate.update(
                "insert into stock_market_close_run(id, symbol, status) values (1, null, 'COMPLETED')"
        );
        jdbcTemplate.update(
                "insert into stock_execution_daily_account_snapshot(close_run_id, simulation_trade_date) values (1, date '2026-07-14')"
        );
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );
        accumulator.recordBuy(
                31L,
                10L,
                buyAmounts("50000.00"),
                LocalDateTime.of(2026, 7, 14, 17, 59)
        );

        accumulator.flush();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_execution_account_day_summary",
                Long.class
        )).isZero();
    }

    @Test
    void rebuildDate_discardsOnlyRequestedDateWithoutQuadraticQueueRemoval() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_rebuild;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
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
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );
        LocalDateTime rebuiltDateExecution = LocalDateTime.of(2026, 7, 14, 17, 58);
        LocalDateTime retainedDateExecution = rebuiltDateExecution.plusDays(1);
        accumulator.recordBuy(41L, 10L, buyAmounts("50000.00"), rebuiltDateExecution);
        accumulator.recordSell(42L, 20L, sellAmounts("100000.00"), retainedDateExecution);

        int rebuilt = accumulator.rebuildDate(rebuiltDateExecution.toLocalDate(), () -> 7);
        accumulator.flush();

        assertThat(rebuilt).isEqualTo(7);
        assertThat(jdbcTemplate.queryForList(
                "select simulation_trade_date, account_id from stock_execution_account_day_summary"
        )).singleElement().satisfies(row -> {
            assertThat(row.get("simulation_trade_date")).isEqualTo(java.sql.Date.valueOf(retainedDateExecution.toLocalDate()));
            assertThat(((Number) row.get("account_id")).longValue()).isEqualTo(42L);
        });
    }

    @Test
    void flush_laterRowFailure_rollsBackEarlierRowsAndRequeuesAllDeltas() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_rollback;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        ));
        jdbcTemplate.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null check (account_id <> 52),
                    execution_count bigint not null,
                    buy_quantity bigint not null,
                    sell_quantity bigint not null,
                    gross_amount decimal(19, 2) not null,
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                new SimpleMeterRegistry()
        );
        LocalDateTime executedAt = LocalDateTime.of(2026, 7, 14, 10, 0);
        accumulator.recordBuy(51L, 10L, buyAmounts("50000.00"), executedAt);
        accumulator.recordSell(52L, 20L, sellAmounts("100000.00"), executedAt);

        boolean failed = false;
        try {
            accumulator.flush();
        } catch (RuntimeException ex) {
            failed = true;
        }
        String outcome = failed
                + ":" + jdbcTemplate.queryForObject(
                        "select count(*) from stock_execution_account_day_summary",
                        Long.class
                )
                + ":" + accumulator.hasPendingDeltas();

        assertThat(outcome).isEqualTo("true:0:true");
    }

    @Test
    void flush_failureWhileNewDeltasFillQueue_requeueNeverExceedsConfiguredBound() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:execution_account_day_summary_requeue_bound;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        BlockingFailureJdbcTemplate jdbcTemplate = new BlockingFailureJdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table stock_execution_account_day_summary (
                    simulation_trade_date date not null,
                    account_id bigint not null check (account_id <> 52),
                    execution_count bigint not null,
                    buy_quantity bigint not null,
                    sell_quantity bigint not null,
                    gross_amount decimal(19, 2) not null,
                    buy_gross_amount decimal(19, 2) not null default 0,
                    sell_gross_amount decimal(19, 2) not null default 0,
                    buy_net_amount decimal(19, 2) not null default 0,
                    sell_net_amount decimal(19, 2) not null default 0,
                    fee_amount decimal(19, 2) not null default 0,
                    tax_amount decimal(19, 2) not null default 0,
                    realized_profit decimal(19, 2) not null default 0,
                    last_executed_at timestamp,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, account_id)
                )
                """);
        createFinalizationTables(jdbcTemplate);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ExecutionAccountDaySummaryAccumulator accumulator = new ExecutionAccountDaySummaryAccumulator(
                jdbcTemplate,
                meterRegistry,
                2,
                2
        );
        LocalDateTime executedAt = LocalDateTime.of(2026, 7, 14, 10, 0);
        accumulator.recordBuy(51L, 10L, buyAmounts("50000.00"), executedAt);
        accumulator.recordSell(52L, 20L, sellAmounts("100000.00"), executedAt);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var failedFlush = executor.submit(accumulator::flush);
            assertThat(jdbcTemplate.awaitFirstSummaryWrite()).isTrue();

            accumulator.recordBuy(61L, 30L, buyAmounts("150000.00"), executedAt);
            accumulator.recordSell(62L, 40L, sellAmounts("200000.00"), executedAt);
            jdbcTemplate.releaseFirstSummaryWrite();

            assertThatThrownBy(failedFlush::get)
                    .isInstanceOf(ExecutionException.class);
        }

        assertThat(meterRegistry.counter(
                "stock.execution.account.day.summary.deltas",
                "result",
                "requeue-overflow"
        ).count()).isEqualTo(2.0d);

        accumulator.flush();

        assertThat(jdbcTemplate.queryForList(
                "select account_id from stock_execution_account_day_summary order by account_id",
                Long.class
        )).containsExactly(61L, 62L);
    }

    private void createFinalizationTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("drop table if exists stock_execution_daily_account_snapshot");
        jdbcTemplate.execute("drop table if exists stock_market_close_run");
        jdbcTemplate.execute("""
                create table stock_market_close_run (
                    id bigint not null primary key,
                    symbol varchar(20),
                    status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_execution_daily_account_snapshot (
                    close_run_id bigint not null,
                    simulation_trade_date date not null
                )
                """);
    }

    private static ExecutionCostCalculator.ExecutionAmounts buyAmounts(String grossAmount) {
        BigDecimal gross = new BigDecimal(grossAmount);
        return new ExecutionCostCalculator.ExecutionAmounts(
                gross,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                gross,
                null
        );
    }

    private static ExecutionCostCalculator.ExecutionAmounts amounts(
            String grossAmount,
            String feeAmount,
            String taxAmount,
            String netAmount,
            String realizedProfit
    ) {
        return new ExecutionCostCalculator.ExecutionAmounts(
                new BigDecimal(grossAmount),
                new BigDecimal(feeAmount),
                new BigDecimal(taxAmount),
                new BigDecimal(netAmount),
                realizedProfit == null ? null : new BigDecimal(realizedProfit)
        );
    }

    private static ExecutionCostCalculator.ExecutionAmounts sellAmounts(String grossAmount) {
        BigDecimal gross = new BigDecimal(grossAmount);
        return new ExecutionCostCalculator.ExecutionAmounts(
                gross,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                gross,
                BigDecimal.ZERO
        );
    }

    private static final class BlockingFailureJdbcTemplate extends JdbcTemplate {

        private final AtomicBoolean firstSummaryWrite = new AtomicBoolean();
        private final CountDownLatch firstSummaryWriteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstSummaryWrite = new CountDownLatch(1);

        private BlockingFailureJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("update stock_execution_account_day_summary")
                    && firstSummaryWrite.compareAndSet(false, true)) {
                firstSummaryWriteStarted.countDown();
                await(releaseFirstSummaryWrite);
            }
            return super.update(sql, args);
        }

        private boolean awaitFirstSummaryWrite() throws InterruptedException {
            return firstSummaryWriteStarted.await(5, TimeUnit.SECONDS);
        }

        private void releaseFirstSummaryWrite() {
            releaseFirstSummaryWrite.countDown();
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the summary write");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to release the summary write", ex);
            }
        }
    }
}
