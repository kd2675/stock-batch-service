package stock.batch.service.execution.biz;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class ExecutionAccountDaySummaryAccumulator {

    private static final String MYSQL_UPSERT_SQL = """
            insert into stock_execution_account_day_summary(
                simulation_trade_date, account_id, execution_count, buy_quantity,
                sell_quantity, gross_amount, last_executed_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?) as incoming
            on duplicate key update
                execution_count = stock_execution_account_day_summary.execution_count + incoming.execution_count,
                buy_quantity = stock_execution_account_day_summary.buy_quantity + incoming.buy_quantity,
                sell_quantity = stock_execution_account_day_summary.sell_quantity + incoming.sell_quantity,
                gross_amount = stock_execution_account_day_summary.gross_amount + incoming.gross_amount,
                last_executed_at = greatest(
                    coalesce(stock_execution_account_day_summary.last_executed_at, incoming.last_executed_at),
                    incoming.last_executed_at
                ),
                updated_at = incoming.updated_at
            """;

    private final ConcurrentLinkedQueue<ExecutionDelta> pending = new ConcurrentLinkedQueue<>();
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final boolean mysql;

    public ExecutionAccountDaySummaryAccumulator(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    public void recordBuy(long accountId, long quantity, BigDecimal grossAmount, LocalDateTime executedAt) {
        pending.add(new ExecutionDelta(
                executedAt.toLocalDate(),
                accountId,
                1L,
                quantity,
                0L,
                grossAmount,
                executedAt
        ));
    }

    public void recordSell(long accountId, long quantity, BigDecimal grossAmount, LocalDateTime executedAt) {
        pending.add(new ExecutionDelta(
                executedAt.toLocalDate(),
                accountId,
                1L,
                0L,
                quantity,
                grossAmount,
                executedAt
        ));
    }

    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    @Scheduled(
            initialDelayString = "${stock.batch.execution-account-summary.initial-delay-ms:5000}",
            fixedDelayString = "${stock.batch.execution-account-summary.flush-fixed-delay-ms:5000}"
    )
    public void flush() {
        List<ExecutionDelta> drained = drain();
        if (drained.isEmpty()) {
            return;
        }
        List<ExecutionDelta> aggregated = aggregate(drained);
        try {
            if (mysql) {
                jdbcTemplate.batchUpdate(
                        MYSQL_UPSERT_SQL,
                        aggregated.stream().map(this::params).toList()
                );
            } else {
                aggregated.forEach(this::upsertPortable);
            }
            meterRegistry.counter("stock.execution.account.day.summary.flushes", "result", "success").increment();
            meterRegistry.counter("stock.execution.account.day.summary.rows").increment(aggregated.size());
        } catch (RuntimeException ex) {
            drained.forEach(pending::add);
            meterRegistry.counter("stock.execution.account.day.summary.flushes", "result", "failure").increment();
            throw ex;
        }
    }

    private List<ExecutionDelta> drain() {
        List<ExecutionDelta> result = new ArrayList<>();
        ExecutionDelta delta;
        while ((delta = pending.poll()) != null) {
            result.add(delta);
        }
        return result;
    }

    private List<ExecutionDelta> aggregate(List<ExecutionDelta> deltas) {
        Map<SummaryKey, ExecutionDelta> byAccountDay = new LinkedHashMap<>();
        for (ExecutionDelta delta : deltas) {
            byAccountDay.merge(
                    new SummaryKey(delta.simulationTradeDate(), delta.accountId()),
                    delta,
                    ExecutionDelta::add
            );
        }
        return List.copyOf(byAccountDay.values());
    }

    private Object[] params(ExecutionDelta delta) {
        return new Object[]{
                delta.simulationTradeDate(),
                delta.accountId(),
                delta.executionCount(),
                delta.buyQuantity(),
                delta.sellQuantity(),
                delta.grossAmount(),
                delta.lastExecutedAt(),
                LocalDateTime.now()
        };
    }

    private void upsertPortable(ExecutionDelta delta) {
        LocalDateTime updatedAt = LocalDateTime.now();
        int updated = jdbcTemplate.update(
                """
                update stock_execution_account_day_summary
                   set execution_count = execution_count + ?,
                       buy_quantity = buy_quantity + ?,
                       sell_quantity = sell_quantity + ?,
                       gross_amount = gross_amount + ?,
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
                    sell_quantity, gross_amount, last_executed_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                delta.simulationTradeDate(),
                delta.accountId(),
                delta.executionCount(),
                delta.buyQuantity(),
                delta.sellQuantity(),
                delta.grossAmount(),
                delta.lastExecutedAt(),
                updatedAt
        );
    }

    private record SummaryKey(LocalDate simulationTradeDate, long accountId) {
    }

    private record ExecutionDelta(
            LocalDate simulationTradeDate,
            long accountId,
            long executionCount,
            long buyQuantity,
            long sellQuantity,
            BigDecimal grossAmount,
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
                    lastExecutedAt.isAfter(other.lastExecutedAt) ? lastExecutedAt : other.lastExecutedAt
            );
        }
    }
}
