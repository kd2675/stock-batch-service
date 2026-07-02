package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.batch.execution.model.VirtualPriceHoldingRow;
import stock.batch.service.batch.execution.model.VirtualPriceOrderCandidate;
import stock.batch.service.execution.biz.ExecutionCostCalculator;

@Component
@RequiredArgsConstructor
public class VirtualPriceExecutionWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ExecutionHoldingJdbcSupport holdingJdbcSupport;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;

    public void insertExecution(
            VirtualPriceOrderCandidate candidate,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                  order_id, account_id, symbol, side, quantity, price,
                  gross_amount, fee_amount, tax_amount, net_amount, realized_profit,
                  source, executed_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VIRTUAL_MARKET_PRICE', ?)
                """,
                candidate.id(),
                candidate.accountId(),
                candidate.symbol(),
                candidate.side(),
                quantity,
                executionPrice,
                amounts.grossAmount(),
                amounts.feeAmount(),
                amounts.taxAmount(),
                amounts.netAmount(),
                amounts.realizedProfit(),
                executedAt
        );
    }

    public void fillOrder(VirtualPriceOrderCandidate candidate, BigDecimal averageFillPrice, LocalDateTime executedAt) {
        jdbcTemplate.update(
                """
                update stock_order
                set status = 'FILLED',
                    filled_quantity = quantity,
                    average_fill_price = ?,
                    reserved_cash = 0,
                    updated_at = ?
                where id = ?
                """,
                averageFillPrice,
                executedAt,
                candidate.id()
        );
    }

    public void creditCash(long accountId, BigDecimal amount, LocalDateTime updatedAt) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?",
                amount,
                updatedAt,
                accountId
        );
    }

    public boolean chargeShortfall(long accountId, BigDecimal shortfall, LocalDateTime updatedAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_account
                set cash_balance = cash_balance - ?,
                    updated_at = ?
                where id = ?
                  and cash_balance >= ?
                """,
                shortfall,
                updatedAt,
                accountId,
                shortfall
        );
        return updatedRows > 0;
    }

    public void rejectBuyOrder(VirtualPriceOrderCandidate candidate, LocalDateTime rejectedAt) {
        jdbcTemplate.update(
                """
                update stock_order
                set status = 'REJECTED',
                    reserved_cash = 0,
                    updated_at = ?
                where id = ?
                """,
                rejectedAt,
                candidate.id()
        );
    }

    public int reduceReservedSellHolding(VirtualPriceHoldingRow holding, long quantity, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_holding
                set quantity = quantity - ?,
                    reserved_quantity = case when reserved_quantity >= ? then reserved_quantity - ? else 0 end,
                    updated_at = ?
                where id = ?
                  and quantity >= ?
                  and reserved_quantity >= ?
                """,
                quantity,
                quantity,
                quantity,
                updatedAt,
                holding.id(),
                quantity,
                quantity
        );
    }

    public void deleteEmptyHolding(long accountId, String symbol) {
        holdingJdbcSupport.deleteEmptyHolding(accountId, symbol);
    }

    public void rejectSellOrder(VirtualPriceOrderCandidate candidate, LocalDateTime rejectedAt) {
        jdbcTemplate.update(
                """
                update stock_order
                set status = 'REJECTED',
                    updated_at = ?
                where id = ?
                """,
                rejectedAt,
                candidate.id()
        );
    }

    public void releaseReservedSellQuantity(long accountId, String symbol, long quantity, LocalDateTime updatedAt) {
        if (quantity <= 0) {
            return;
        }
        holdingReservationJdbcSupport.releaseReservedSellQuantity(accountId, symbol, quantity, updatedAt);
    }

    public void upsertHolding(
            long accountId,
            String symbol,
            long quantity,
            BigDecimal costAmount,
            LocalDateTime updatedAt
    ) {
        holdingJdbcSupport.upsertHolding(accountId, symbol, quantity, costAmount, updatedAt);
    }
}
