package stock.batch.service.execution.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import stock.batch.service.batch.execution.model.VirtualPriceHoldingRow;
import stock.batch.service.batch.execution.model.VirtualPriceOrderCandidate;
import stock.batch.service.batch.execution.reader.VirtualPriceExecutionReader;
import stock.batch.service.batch.execution.writer.VirtualPriceExecutionWriter;
import stock.batch.service.simulation.SimulationClockService;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final ExecutionCostCalculator executionCostCalculator;
    private final VirtualPriceExecutionReader virtualPriceExecutionReader;
    private final VirtualPriceExecutionWriter virtualPriceExecutionWriter;
    private final SimulationClockService simulationClockService;

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
        LocalDateTime executedAt = simulationClockService.currentMarketDateTime();
        ExecutionCostCalculator.ExecutionAmounts amounts;
        if ("BUY".equals(candidate.side())) {
            amounts = executionCostCalculator.buy(remainingQuantity, executionPrice);
            if (!executeBuy(candidate, remainingQuantity, amounts, executedAt)) {
                return;
            }
        } else {
            amounts = executeSell(candidate, remainingQuantity, executionPrice, executedAt);
            if (amounts == null) {
                return;
            }
        }

        BigDecimal averageFillPrice = calculateAverageFillPrice(candidate, remainingQuantity, executionPrice);

        virtualPriceExecutionWriter.insertExecution(candidate, remainingQuantity, executionPrice, amounts, executedAt);
        virtualPriceExecutionWriter.fillOrder(candidate, averageFillPrice, executedAt);
    }

    private BigDecimal calculateAverageFillPrice(VirtualPriceOrderCandidate candidate, long fillQuantity, BigDecimal executionPrice) {
        return AverageFillPriceCalculator.calculate(
                candidate.averageFillPrice(),
                candidate.filledQuantity(),
                fillQuantity,
                executionPrice
        );
    }

    private boolean executeBuy(
            VirtualPriceOrderCandidate candidate,
            long quantity,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        BigDecimal reservedCost = candidate.reservedCash();
        BigDecimal actualCost = amounts.netAmount();
        BigDecimal release = reservedCost.subtract(actualCost).max(BigDecimal.ZERO);
        BigDecimal shortfall = actualCost.subtract(reservedCost).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) > 0 && !chargeShortfall(candidate, shortfall, executedAt)) {
            rejectBuyOrder(candidate, executedAt);
            return false;
        }
        virtualPriceExecutionWriter.creditCash(candidate.accountId(), release, executedAt);
        upsertHolding(candidate.accountId(), candidate.symbol(), quantity, amounts.netAmount(), executedAt);
        return true;
    }

    private boolean chargeShortfall(VirtualPriceOrderCandidate candidate, BigDecimal shortfall, LocalDateTime executedAt) {
        return virtualPriceExecutionWriter.chargeShortfall(candidate.accountId(), shortfall, executedAt);
    }

    private void rejectBuyOrder(VirtualPriceOrderCandidate candidate, LocalDateTime rejectedAt) {
        virtualPriceExecutionWriter.creditCash(candidate.accountId(), candidate.reservedCash(), rejectedAt);
        virtualPriceExecutionWriter.rejectBuyOrder(candidate, rejectedAt);
    }

    private ExecutionCostCalculator.ExecutionAmounts executeSell(
            VirtualPriceOrderCandidate candidate,
            long quantity,
            BigDecimal executionPrice,
            LocalDateTime executedAt
    ) {
        VirtualPriceHoldingRow holding = virtualPriceExecutionReader.findHoldingForUpdate(candidate.accountId(), candidate.symbol());
        if (holding == null || holding.quantity() < quantity) {
            rejectSellOrder(candidate, executedAt);
            return null;
        }
        ExecutionCostCalculator.ExecutionAmounts amounts = executionCostCalculator.sell(quantity, executionPrice, holding.averagePrice());
        int updatedRows = virtualPriceExecutionWriter.reduceReservedSellHolding(holding, quantity, executedAt);
        if (updatedRows == 0) {
            rejectSellOrder(candidate, executedAt);
            return null;
        }
        virtualPriceExecutionWriter.creditCash(candidate.accountId(), amounts.netAmount(), executedAt);
        virtualPriceExecutionWriter.deleteEmptyHolding(candidate.accountId(), candidate.symbol());
        return amounts;
    }

    private void rejectSellOrder(VirtualPriceOrderCandidate candidate, LocalDateTime rejectedAt) {
        virtualPriceExecutionWriter.releaseReservedSellQuantity(
                candidate.accountId(),
                candidate.symbol(),
                candidate.quantity() - candidate.filledQuantity(),
                rejectedAt
        );
        virtualPriceExecutionWriter.rejectSellOrder(candidate, rejectedAt);
    }

    private void upsertHolding(long accountId, String symbol, long quantity, BigDecimal costAmount, LocalDateTime executedAt) {
        virtualPriceExecutionWriter.upsertHolding(accountId, symbol, quantity, costAmount, executedAt);
    }

}
