package stock.batch.service.execution.biz;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static stock.batch.service.batch.config.BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER;

@Component
public class ExecutionAccountDaySummaryAccumulator {

    private static final int MYSQL_UPSERT_ROW_CHUNK_SIZE = 500;
    private static final int MAX_FLUSH_DELTA_BATCH_SIZE = 5_000;
    private static final int MAX_PENDING_DELTA_COUNT = 1_000_000;
    private static final String MYSQL_UPSERT_SQL = """
            insert into stock_execution_account_day_summary(
                simulation_trade_date, account_id, execution_count, buy_quantity,
                sell_quantity, gross_amount, buy_gross_amount, sell_gross_amount,
                buy_net_amount, sell_net_amount, fee_amount, tax_amount,
                realized_profit, last_executed_at, updated_at
            ) values %s as incoming
            on duplicate key update
                execution_count = stock_execution_account_day_summary.execution_count + incoming.execution_count,
                buy_quantity = stock_execution_account_day_summary.buy_quantity + incoming.buy_quantity,
                sell_quantity = stock_execution_account_day_summary.sell_quantity + incoming.sell_quantity,
                gross_amount = stock_execution_account_day_summary.gross_amount + incoming.gross_amount,
                buy_gross_amount = stock_execution_account_day_summary.buy_gross_amount + incoming.buy_gross_amount,
                sell_gross_amount = stock_execution_account_day_summary.sell_gross_amount + incoming.sell_gross_amount,
                buy_net_amount = stock_execution_account_day_summary.buy_net_amount + incoming.buy_net_amount,
                sell_net_amount = stock_execution_account_day_summary.sell_net_amount + incoming.sell_net_amount,
                fee_amount = stock_execution_account_day_summary.fee_amount + incoming.fee_amount,
                tax_amount = stock_execution_account_day_summary.tax_amount + incoming.tax_amount,
                realized_profit = stock_execution_account_day_summary.realized_profit + incoming.realized_profit,
                last_executed_at = greatest(
                    coalesce(stock_execution_account_day_summary.last_executed_at, incoming.last_executed_at),
                    incoming.last_executed_at
                ),
                updated_at = incoming.updated_at
            """;

    private final ConcurrentHashMap<SummaryKey, ExecutionDelta> pending = new ConcurrentHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final boolean mysql;
    private final int maxPendingDeltas;
    private final int flushBatchSize;

    public ExecutionAccountDaySummaryAccumulator(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, 100_000, 5_000, localTransactionManager(jdbcTemplate));
    }

    ExecutionAccountDaySummaryAccumulator(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            int maxPendingDeltas,
            int flushBatchSize
    ) {
        this(
                jdbcTemplate,
                meterRegistry,
                maxPendingDeltas,
                flushBatchSize,
                localTransactionManager(jdbcTemplate)
        );
    }

    @Autowired
    public ExecutionAccountDaySummaryAccumulator(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            @Value("${stock.batch.execution-account-summary.max-pending-deltas:100000}") int maxPendingDeltas,
            @Value("${stock.batch.execution-account-summary.flush-batch-size:5000}") int flushBatchSize,
            @Qualifier(BUSINESS_TRANSACTION_MANAGER) PlatformTransactionManager transactionManager
    ) {
        if (maxPendingDeltas <= 0
                || maxPendingDeltas > MAX_PENDING_DELTA_COUNT
                || flushBatchSize <= 0
                || flushBatchSize > maxPendingDeltas
                || flushBatchSize > MAX_FLUSH_DELTA_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "execution account summary queue limits are invalid: maxPendingDeltas must be between 1 and %d, "
                            .formatted(MAX_PENDING_DELTA_COUNT)
                            + "flushBatchSize must be between 1 and %d and not exceed maxPendingDeltas"
                            .formatted(MAX_FLUSH_DELTA_BATCH_SIZE)
            );
        }
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxPendingDeltas = maxPendingDeltas;
        this.flushBatchSize = flushBatchSize;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    public void recordBuy(
            long accountId,
            long quantity,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        offer(new ExecutionDelta(
                executedAt.toLocalDate(),
                accountId,
                1L,
                quantity,
                0L,
                amounts.grossAmount(),
                amounts.grossAmount(),
                BigDecimal.ZERO,
                amounts.netAmount(),
                BigDecimal.ZERO,
                amounts.feeAmount(),
                amounts.taxAmount(),
                zeroIfNull(amounts.realizedProfit()),
                executedAt
        ));
    }

    public void recordSell(
            long accountId,
            long quantity,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        offer(new ExecutionDelta(
                executedAt.toLocalDate(),
                accountId,
                1L,
                0L,
                quantity,
                amounts.grossAmount(),
                BigDecimal.ZERO,
                amounts.grossAmount(),
                BigDecimal.ZERO,
                amounts.netAmount(),
                amounts.feeAmount(),
                amounts.taxAmount(),
                zeroIfNull(amounts.realizedProfit()),
                executedAt
        ));
    }

    public boolean hasPendingDeltas() {
        return pendingCount.get() > 0;
    }

    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    public synchronized int flush() {
        List<ExecutionDelta> drained = drain();
        if (drained.isEmpty()) {
            return 0;
        }
        try {
            FlushResult result = transactionTemplate.execute(status -> persistAggregated(drained));
            if (result == null) {
                throw new IllegalStateException("Execution account summary flush returned no transaction result");
            }
            if (result.finalizedDeltaCount() > 0) {
                meterRegistry.counter(
                        "stock.execution.account.day.summary.deltas",
                        "result",
                        "discarded-after-finalization"
                ).increment(result.finalizedDeltaCount());
            }
            if (result.writtenRows() == 0) {
                return 0;
            }
            meterRegistry.counter("stock.execution.account.day.summary.flushes", "result", "success").increment();
            meterRegistry.counter("stock.execution.account.day.summary.rows").increment(result.writtenRows());
            return result.writtenRows();
        } catch (RuntimeException ex) {
            drained.forEach(this::requeue);
            meterRegistry.counter("stock.execution.account.day.summary.flushes", "result", "failure").increment();
            throw ex;
        }
    }

    private FlushResult persistAggregated(List<ExecutionDelta> aggregated) {
        Set<LocalDate> finalizedDates = findFinalizedDates(aggregated);
        List<ExecutionDelta> writable = aggregated.stream()
                .filter(delta -> !finalizedDates.contains(delta.simulationTradeDate()))
                .toList();
        long finalizedDeltaCount = aggregated.stream()
                .filter(delta -> finalizedDates.contains(delta.simulationTradeDate()))
                .mapToLong(ExecutionDelta::executionCount)
                .sum();
        if (writable.isEmpty()) {
            return new FlushResult(0, finalizedDeltaCount);
        }
        LocalDateTime updatedAt = LocalDateTime.now();
        if (mysql) {
            upsertMysql(writable, updatedAt);
        } else {
            writable.forEach(delta -> upsertPortable(delta, updatedAt));
        }
        return new FlushResult(writable.size(), finalizedDeltaCount);
    }

    private void upsertMysql(List<ExecutionDelta> writable, LocalDateTime updatedAt) {
        for (int start = 0; start < writable.size(); start += MYSQL_UPSERT_ROW_CHUNK_SIZE) {
            int end = Math.min(writable.size(), start + MYSQL_UPSERT_ROW_CHUNK_SIZE);
            List<ExecutionDelta> chunk = writable.subList(start, end);
            String values = String.join(",", java.util.Collections.nCopies(
                    chunk.size(),
                    "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ));
            List<Object> parameters = new ArrayList<>(chunk.size() * 15);
            for (ExecutionDelta delta : chunk) {
                java.util.Collections.addAll(parameters, params(delta, updatedAt));
            }
            int affectedRows = jdbcTemplate.update(
                    MYSQL_UPSERT_SQL.formatted(values),
                    parameters.toArray()
            );
            if (affectedRows < chunk.size()) {
                throw new IllegalStateException(
                        "Execution account summary UPSERT count mismatch: expectedAtLeast=%d, actual=%d"
                                .formatted(chunk.size(), affectedRows)
                );
            }
        }
    }

    public synchronized int rebuildDate(LocalDate businessDate, IntSupplier rebuildAction) {
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
        if (rebuildAction == null) {
            throw new IllegalArgumentException("rebuildAction is required");
        }
        discardDateInternal(businessDate);
        return rebuildAction.getAsInt();
    }

    private int discardDateInternal(LocalDate businessDate) {
        if (businessDate == null) {
            return 0;
        }
        int removed = 0;
        for (var entry : pending.entrySet()) {
            if (businessDate.equals(entry.getKey().simulationTradeDate())
                    && pending.remove(entry.getKey(), entry.getValue())) {
                pendingCount.decrementAndGet();
                removed++;
            }
        }
        return removed;
    }

    private Set<LocalDate> findFinalizedDates(List<ExecutionDelta> deltas) {
        List<LocalDate> businessDates = deltas.stream()
                .map(ExecutionDelta::simulationTradeDate)
                .distinct()
                .toList();
        if (businessDates.isEmpty()) {
            return Set.of();
        }
        return jdbcClient.sql(
                        """
                        select distinct snapshot.simulation_trade_date
                          from stock_execution_daily_account_snapshot snapshot
                          join stock_market_close_run close_run
                            on close_run.id = snapshot.close_run_id
                           and close_run.symbol is null
                           and close_run.status = 'COMPLETED'
                         where snapshot.simulation_trade_date in (:businessDates)
                        """
                )
                .param("businessDates", businessDates)
                .query(LocalDate.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    private void offer(ExecutionDelta delta) {
        if (!mergePending(delta)) {
            meterRegistry.counter("stock.execution.account.day.summary.deltas", "result", "overflow").increment();
        }
    }

    private void requeue(ExecutionDelta delta) {
        if (!mergePending(delta)) {
            meterRegistry.counter(
                    "stock.execution.account.day.summary.deltas",
                    "result",
                    "requeue-overflow"
            ).increment();
        }
    }

    private boolean mergePending(ExecutionDelta delta) {
        SummaryKey key = new SummaryKey(delta.simulationTradeDate(), delta.accountId());
        AtomicBoolean accepted = new AtomicBoolean();
        pending.compute(key, (ignored, current) -> {
            if (current != null) {
                accepted.set(true);
                return current.add(delta);
            }
            int reserved = pendingCount.incrementAndGet();
            if (reserved > maxPendingDeltas) {
                pendingCount.decrementAndGet();
                return null;
            }
            accepted.set(true);
            return delta;
        });
        return accepted.get();
    }

    private List<ExecutionDelta> drain() {
        List<ExecutionDelta> result = new ArrayList<>(Math.min(pendingCount.get(), flushBatchSize));
        for (var entry : pending.entrySet()) {
            if (result.size() >= flushBatchSize) {
                break;
            }
            if (pending.remove(entry.getKey(), entry.getValue())) {
                pendingCount.decrementAndGet();
                result.add(entry.getValue());
            }
        }
        return result;
    }

    private Object[] params(ExecutionDelta delta, LocalDateTime updatedAt) {
        return new Object[]{
                delta.simulationTradeDate(),
                delta.accountId(),
                delta.executionCount(),
                delta.buyQuantity(),
                delta.sellQuantity(),
                delta.grossAmount(),
                delta.buyGrossAmount(),
                delta.sellGrossAmount(),
                delta.buyNetAmount(),
                delta.sellNetAmount(),
                delta.feeAmount(),
                delta.taxAmount(),
                delta.realizedProfit(),
                delta.lastExecutedAt(),
                updatedAt
        };
    }

    private void upsertPortable(ExecutionDelta delta, LocalDateTime updatedAt) {
        int updated = jdbcTemplate.update(
                """
                update stock_execution_account_day_summary
                   set execution_count = execution_count + ?,
                       buy_quantity = buy_quantity + ?,
                       sell_quantity = sell_quantity + ?,
                       gross_amount = gross_amount + ?,
                       buy_gross_amount = buy_gross_amount + ?,
                       sell_gross_amount = sell_gross_amount + ?,
                       buy_net_amount = buy_net_amount + ?,
                       sell_net_amount = sell_net_amount + ?,
                       fee_amount = fee_amount + ?,
                       tax_amount = tax_amount + ?,
                       realized_profit = realized_profit + ?,
                       last_executed_at = case
                           when last_executed_at is null or last_executed_at < ? then ?
                           else last_executed_at
                       end,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and account_id = ?
                """,
                delta.executionCount(),
                delta.buyQuantity(),
                delta.sellQuantity(),
                delta.grossAmount(),
                delta.buyGrossAmount(),
                delta.sellGrossAmount(),
                delta.buyNetAmount(),
                delta.sellNetAmount(),
                delta.feeAmount(),
                delta.taxAmount(),
                delta.realizedProfit(),
                delta.lastExecutedAt(),
                delta.lastExecutedAt(),
                updatedAt,
                delta.simulationTradeDate(),
                delta.accountId()
        );
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                insert into stock_execution_account_day_summary(
                    simulation_trade_date, account_id, execution_count, buy_quantity,
                    sell_quantity, gross_amount, buy_gross_amount, sell_gross_amount,
                    buy_net_amount, sell_net_amount, fee_amount, tax_amount,
                    realized_profit, last_executed_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                delta.simulationTradeDate(),
                delta.accountId(),
                delta.executionCount(),
                delta.buyQuantity(),
                delta.sellQuantity(),
                delta.grossAmount(),
                delta.buyGrossAmount(),
                delta.sellGrossAmount(),
                delta.buyNetAmount(),
                delta.sellNetAmount(),
                delta.feeAmount(),
                delta.taxAmount(),
                delta.realizedProfit(),
                delta.lastExecutedAt(),
                updatedAt
        );
    }

    private record SummaryKey(LocalDate simulationTradeDate, long accountId) {
    }

    private record FlushResult(int writtenRows, long finalizedDeltaCount) {
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbcTemplate) {
        return new DataSourceTransactionManager(
                Objects.requireNonNull(jdbcTemplate.getDataSource(), "JdbcTemplate DataSource is required")
        );
    }

    private record ExecutionDelta(
            LocalDate simulationTradeDate,
            long accountId,
            long executionCount,
            long buyQuantity,
            long sellQuantity,
            BigDecimal grossAmount,
            BigDecimal buyGrossAmount,
            BigDecimal sellGrossAmount,
            BigDecimal buyNetAmount,
            BigDecimal sellNetAmount,
            BigDecimal feeAmount,
            BigDecimal taxAmount,
            BigDecimal realizedProfit,
            LocalDateTime lastExecutedAt
    ) {
        private ExecutionDelta add(ExecutionDelta other) {
            return new ExecutionDelta(
                    simulationTradeDate,
                    accountId,
                    executionCount + other.executionCount,
                    buyQuantity + other.buyQuantity,
                    sellQuantity + other.sellQuantity,
                    grossAmount.add(other.grossAmount),
                    buyGrossAmount.add(other.buyGrossAmount),
                    sellGrossAmount.add(other.sellGrossAmount),
                    buyNetAmount.add(other.buyNetAmount),
                    sellNetAmount.add(other.sellNetAmount),
                    feeAmount.add(other.feeAmount),
                    taxAmount.add(other.taxAmount),
                    realizedProfit.add(other.realizedProfit),
                    lastExecutedAt.isAfter(other.lastExecutedAt) ? lastExecutedAt : other.lastExecutedAt
            );
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
