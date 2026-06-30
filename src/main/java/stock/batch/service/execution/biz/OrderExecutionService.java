package stock.batch.service.execution.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import stock.batch.service.batch.execution.model.VirtualPriceHoldingRow;
import stock.batch.service.batch.execution.model.VirtualPriceOrderCandidate;
import stock.batch.service.batch.execution.reader.VirtualPriceExecutionReader;
import stock.batch.service.batch.execution.writer.VirtualPriceExecutionWriter;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final ExecutionCostCalculator executionCostCalculator;
    private final VirtualPriceExecutionReader virtualPriceExecutionReader;
    private final VirtualPriceExecutionWriter virtualPriceExecutionWriter;

    @Value("${stock.batch.execution.scan-limit:100}")
    private int scanLimit;

    @Transactional
    public int executeEligibleOrders() {
        List<VirtualPriceOrderCandidate> candidates = virtualPriceExecutionReader.findCandidatesForUpdate(scanLimit);

        int executedCount = 0;
        for (VirtualPriceOrderCandidate candidate : candidates) {
            if (isExecutable(candidate)) {
                execute(candidate);
                executedCount++;
            }
        }
        return executedCount;
    }

    private boolean isExecutable(VirtualPriceOrderCandidate candidate) {
        if ("MARKET".equals(candidate.orderType())) {
            return true;
        }
        if (candidate.limitPrice() == null) {
            return false;
        }
        if ("BUY".equals(candidate.side())) {
            return candidate.currentPrice().compareTo(candidate.limitPrice()) <= 0;
        }
        return candidate.currentPrice().compareTo(candidate.limitPrice()) >= 0;
    }

    private void execute(VirtualPriceOrderCandidate candidate) {
        long remainingQuantity = candidate.quantity() - candidate.filledQuantity();
        if (remainingQuantity <= 0) {
            return;
        }

        BigDecimal executionPrice = candidate.currentPrice();
        ExecutionCostCalculator.ExecutionAmounts amounts;
        if ("BUY".equals(candidate.side())) {
            amounts = executionCostCalculator.buy(remainingQuantity, executionPrice);
            if (!executeBuy(candidate, remainingQuantity, amounts)) {
                return;
            }
        } else {
            amounts = executeSell(candidate, remainingQuantity, executionPrice);
            if (amounts == null) {
                return;
            }
        }

        LocalDateTime executedAt = LocalDateTime.now();
        BigDecimal averageFillPrice = calculateAverageFillPrice(candidate, remainingQuantity, executionPrice);

        virtualPriceExecutionWriter.insertExecution(candidate, remainingQuantity, executionPrice, amounts, executedAt);
        virtualPriceExecutionWriter.fillOrder(candidate, averageFillPrice, executedAt);
    }

    private BigDecimal calculateAverageFillPrice(VirtualPriceOrderCandidate candidate, long fillQuantity, BigDecimal executionPrice) {
        BigDecimal previousAverage = candidate.averageFillPrice() == null ? BigDecimal.ZERO : candidate.averageFillPrice();
        BigDecimal previousAmount = previousAverage.multiply(BigDecimal.valueOf(candidate.filledQuantity()));
        BigDecimal nextAmount = previousAmount.add(executionPrice.multiply(BigDecimal.valueOf(fillQuantity)));
        long nextFilledQuantity = candidate.filledQuantity() + fillQuantity;
        return nextAmount.divide(BigDecimal.valueOf(nextFilledQuantity), 2, RoundingMode.HALF_UP);
    }

    private boolean executeBuy(
            VirtualPriceOrderCandidate candidate,
            long quantity,
            ExecutionCostCalculator.ExecutionAmounts amounts
    ) {
        BigDecimal reservedCost = candidate.reservedCash();
        BigDecimal actualCost = amounts.netAmount();
        BigDecimal release = reservedCost.subtract(actualCost).max(BigDecimal.ZERO);
        BigDecimal shortfall = actualCost.subtract(reservedCost).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) > 0 && !chargeShortfall(candidate, shortfall)) {
            rejectBuyOrder(candidate);
            return false;
        }
        virtualPriceExecutionWriter.creditCash(candidate.accountId(), release, LocalDateTime.now());
        upsertHolding(candidate.accountId(), candidate.symbol(), quantity, amounts.netAmount());
        return true;
    }

    private boolean chargeShortfall(VirtualPriceOrderCandidate candidate, BigDecimal shortfall) {
        return virtualPriceExecutionWriter.chargeShortfall(candidate.accountId(), shortfall, LocalDateTime.now());
    }

    private void rejectBuyOrder(VirtualPriceOrderCandidate candidate) {
        LocalDateTime rejectedAt = LocalDateTime.now();
        virtualPriceExecutionWriter.creditCash(candidate.accountId(), candidate.reservedCash(), rejectedAt);
        virtualPriceExecutionWriter.rejectBuyOrder(candidate, rejectedAt);
    }

    private ExecutionCostCalculator.ExecutionAmounts executeSell(
            VirtualPriceOrderCandidate candidate,
            long quantity,
            BigDecimal executionPrice
    ) {
        VirtualPriceHoldingRow holding = virtualPriceExecutionReader.findHoldingForUpdate(candidate.accountId(), candidate.symbol());
        if (holding == null || holding.quantity() < quantity) {
            rejectSellOrder(candidate);
            return null;
        }
        ExecutionCostCalculator.ExecutionAmounts amounts = executionCostCalculator.sell(quantity, executionPrice, holding.averagePrice());
        int updatedRows = virtualPriceExecutionWriter.reduceReservedSellHolding(holding, quantity, LocalDateTime.now());
        if (updatedRows == 0) {
            rejectSellOrder(candidate);
            return null;
        }
        virtualPriceExecutionWriter.creditCash(candidate.accountId(), amounts.netAmount(), LocalDateTime.now());
        virtualPriceExecutionWriter.deleteEmptyHolding(candidate.accountId(), candidate.symbol());
        return amounts;
    }

    private void rejectSellOrder(VirtualPriceOrderCandidate candidate) {
        LocalDateTime rejectedAt = LocalDateTime.now();
        virtualPriceExecutionWriter.releaseReservedSellQuantity(
                candidate.accountId(),
                candidate.symbol(),
                candidate.quantity() - candidate.filledQuantity(),
                rejectedAt
        );
        virtualPriceExecutionWriter.rejectSellOrder(candidate, rejectedAt);
    }

    private void upsertHolding(long accountId, String symbol, long quantity, BigDecimal costAmount) {
        virtualPriceExecutionWriter.upsertHolding(accountId, symbol, quantity, costAmount, LocalDateTime.now());
    }

}
