package stock.batch.service.execution.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.batch.execution.reader.OrderBookExecutionReader;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;
import stock.batch.service.batch.execution.writer.OrderBookPriceWriter;
import stock.batch.service.simulation.SimulationClockService;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternalOrderBookExecutionService {

    private final ExecutionCostCalculator executionCostCalculator;
    private final OrderBookExecutionReader orderBookExecutionReader;
    private final OrderBookExecutionWriter orderBookExecutionWriter;
    private final OrderBookPriceWriter orderBookPriceWriter;
    private final StockPriceRedisPublisher priceRedisPublisher;
    private final SimulationClockService simulationClockService;
    private final TransactionTemplate transactionTemplate;

    @Value("${stock.batch.execution.scan-limit:300}")
    private int scanLimit;

    @Value("${stock.batch.execution.buy-candidate-scan-limit:20}")
    private int buyCandidateScanLimit;

    @Value("${stock.batch.execution.deadlock-retry-max-attempts:3}")
    private int deadlockRetryMaxAttempts;

    @Value("${stock.batch.execution.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMillis;

    public int executeEligibleOrders() {
        int matchCount = 0;
        for (String symbol : orderBookExecutionReader.findExecutableSymbols()) {
            while (matchCount < scanLimit && matchNextWithRetry(symbol)) {
                matchCount++;
            }
            if (matchCount >= scanLimit) {
                return matchCount;
            }
        }
        return matchCount;
    }

    private boolean matchNextWithRetry(String symbol) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Boolean matched = transactionTemplate.execute(status -> matchNext(symbol));
                return Boolean.TRUE.equals(matched);
            } catch (CannotAcquireLockException ex) {
                if (attempt >= attempts) {
                    throw ex;
                }
                sleepBeforeRetry(symbol, attempt, ex);
            }
        }
        return false;
    }

    private void sleepBeforeRetry(String symbol, int attempt, CannotAcquireLockException ex) {
        long backoffMillis = Math.max(0L, deadlockRetryBackoffMillis) * attempt;
        log.debug(
                "Order book match retry after lock acquisition failure: symbol={}, attempt={}, backoffMs={}, reason={}",
                symbol,
                attempt,
                backoffMillis,
                ex.getMessage()
        );
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying order book match", interrupted);
        }
    }

    private boolean matchNext(String symbol) {
        int candidateLimit = Math.max(1, Math.min(scanLimit, buyCandidateScanLimit));
        for (OrderBookOrderRow buyOrder : orderBookExecutionReader.findBestBuyCandidates(symbol, candidateLimit)) {
            OrderBookOrderRow sellOrder = orderBookExecutionReader.findBestSell(symbol, buyOrder);
            if (sellOrder != null) {
                return matchOrders(buyOrder, sellOrder);
            }
        }
        return false;
    }

    private boolean matchOrders(OrderBookOrderRow buyOrder, OrderBookOrderRow sellOrder) {
        long quantity = Math.min(buyOrder.remainingQuantity(), sellOrder.remainingQuantity());
        if (quantity <= 0) {
            return false;
        }

        BigDecimal executionPrice = resolveExecutionPrice(buyOrder, sellOrder);
        if (executionPrice == null) {
            return false;
        }
        LocalDateTime executedAt = simulationClockService.currentMarketDateTime();
        ExecutionCostCalculator.ExecutionAmounts buyAmounts = executionCostCalculator.buy(quantity, executionPrice);
        ExecutionCostCalculator.ExecutionAmounts sellAmounts = resolveSellAmounts(sellOrder, quantity, executionPrice);
        if (sellAmounts == null) {
            rejectSellOrder(sellOrder, executedAt);
            return false;
        }
        if (!hasEnoughBuyCash(buyOrder, quantity, executionPrice, buyAmounts)) {
            rejectBuyOrder(buyOrder, executedAt);
            return false;
        }
        if (!executeSell(sellOrder, quantity, executionPrice, sellAmounts, executedAt)) {
            return false;
        }
        executeBuy(buyOrder, quantity, executionPrice, buyAmounts, executedAt);
        orderBookPriceWriter.updateLastTradePrice(buyOrder.symbol(), executionPrice, executedAt);
        priceRedisPublisher.publish(buyOrder.symbol(), executionPrice, executedAt, OrderBookPriceWriter.PROVIDER);
        return true;
    }

    private BigDecimal resolveExecutionPrice(OrderBookOrderRow buyOrder, OrderBookOrderRow sellOrder) {
        if (sellOrder.limitPrice() != null) {
            return sellOrder.limitPrice();
        }
        return buyOrder.limitPrice();
    }

    private boolean hasEnoughBuyCash(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts
    ) {
        BigDecimal reservedForMatchedQuantity = calculateReservedCashToRelease(order, quantity, executionPrice);
        BigDecimal shortfall = amounts.netAmount().subtract(reservedForMatchedQuantity).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return orderBookExecutionReader.hasEnoughCash(order.accountId(), shortfall);
    }

    private void executeBuy(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        BigDecimal reservedForMatchedQuantity = calculateReservedCashToRelease(order, quantity, executionPrice);
        BigDecimal actualCost = amounts.netAmount();
        BigDecimal release = reservedForMatchedQuantity.subtract(actualCost).max(BigDecimal.ZERO);
        BigDecimal shortfall = actualCost.subtract(reservedForMatchedQuantity).max(BigDecimal.ZERO);
        orderBookExecutionWriter.debitCash(order.accountId(), shortfall, executedAt);
        orderBookExecutionWriter.creditCash(order.accountId(), release, executedAt);
        upsertHolding(order.accountId(), order.symbol(), quantity, amounts.netAmount(), executedAt);
        orderBookExecutionWriter.insertExecution(order, quantity, executionPrice, amounts, executedAt);
        updateOrderAfterFill(order, quantity, executionPrice, reservedForMatchedQuantity, executedAt);
    }

    private BigDecimal calculateReservedCashToRelease(OrderBookOrderRow order, long quantity, BigDecimal executionPrice) {
        if ("MARKET".equals(order.orderType())) {
            if (order.remainingQuantity() == quantity) {
                return order.reservedCash();
            }
            BigDecimal reservedPerShare = order.reservedCash()
                    .divide(BigDecimal.valueOf(order.remainingQuantity()), 2, RoundingMode.HALF_UP);
            return reservedPerShare.multiply(BigDecimal.valueOf(quantity));
        }
        BigDecimal expectedReserve = order.limitPrice().multiply(BigDecimal.valueOf(quantity));
        return expectedReserve.min(order.reservedCash());
    }

    private ExecutionCostCalculator.ExecutionAmounts resolveSellAmounts(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice
    ) {
        OrderBookHoldingRow holding = orderBookExecutionReader.findHoldingForUpdate(order.accountId(), order.symbol());
        if (holding == null || holding.quantity() < quantity || holding.reservedQuantity() < quantity) {
            return null;
        }
        return executionCostCalculator.sell(quantity, executionPrice, holding.averagePrice());
    }

    private boolean executeSell(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        int updatedRows = orderBookExecutionWriter.reduceReservedSellHolding(order, quantity, executedAt);
        if (updatedRows == 0) {
            rejectSellOrder(order, executedAt);
            return false;
        }
        orderBookExecutionWriter.creditCash(order.accountId(), amounts.netAmount(), executedAt);
        orderBookExecutionWriter.deleteEmptyHolding(order.accountId(), order.symbol());
        orderBookExecutionWriter.insertExecution(order, quantity, executionPrice, amounts, executedAt);
        updateOrderAfterFill(order, quantity, executionPrice, BigDecimal.ZERO, executedAt);
        return true;
    }

    private void updateOrderAfterFill(
            OrderBookOrderRow order,
            long fillQuantity,
            BigDecimal executionPrice,
            BigDecimal reservedCashToRelease,
            LocalDateTime executedAt
    ) {
        long nextFilledQuantity = order.filledQuantity() + fillQuantity;
        BigDecimal nextAverageFillPrice = calculateAverageFillPrice(order, fillQuantity, executionPrice);
        BigDecimal nextReservedCash = order.reservedCash().subtract(reservedCashToRelease).max(BigDecimal.ZERO);
        orderBookExecutionWriter.updateOrderAfterFill(
                order,
                nextFilledQuantity,
                nextAverageFillPrice,
                nextReservedCash,
                executedAt
        );
    }

    private void rejectBuyOrder(OrderBookOrderRow order, LocalDateTime rejectedAt) {
        orderBookExecutionWriter.creditCash(order.accountId(), order.reservedCash(), rejectedAt);
        orderBookExecutionWriter.rejectBuyOrder(order, rejectedAt);
    }

    private void rejectSellOrder(OrderBookOrderRow order, LocalDateTime rejectedAt) {
        orderBookExecutionWriter.releaseReservedSellQuantity(order, rejectedAt);
        orderBookExecutionWriter.rejectSellOrder(order, rejectedAt);
    }

    private BigDecimal calculateAverageFillPrice(OrderBookOrderRow order, long fillQuantity, BigDecimal executionPrice) {
        return AverageFillPriceCalculator.calculate(
                order.averageFillPrice(),
                order.filledQuantity(),
                fillQuantity,
                executionPrice
        );
    }

    private void upsertHolding(long accountId, String symbol, long quantity, BigDecimal costAmount, LocalDateTime executedAt) {
        orderBookExecutionWriter.upsertHolding(
                accountId,
                symbol,
                quantity,
                costAmount,
                executedAt
        );
    }

}
