package stock.batch.service.marketclose.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.simulation.SimulationClockService;

@Service
@RequiredArgsConstructor
public class MarketCloseRolloverService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final MarketCloseRolloverWriter writer;
    private final SimulationClockService simulationClockService;

    @Transactional
    public int rolloverClosingPrices() {
        return rolloverClosingPrices(null);
    }

    @Transactional
    public int rolloverClosingPrices(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        LocalDateTime closedAt = simulationClockService.currentMarketDateTime();
        long closeRunId = writer.createCloseRun(normalizedSymbol, simulationClockService.currentDate(), closedAt);
        int cancelledOrderCount = cancelOpenOrderBookOrders(normalizedSymbol, closedAt);
        int holdingSnapshotCount = writer.snapshotHoldings(closeRunId, normalizedSymbol, closedAt);
        int priceRolloverCount = writer.rolloverClosingPrices(normalizedSymbol);
        writer.completeCloseRun(
                closeRunId,
                cancelledOrderCount,
                holdingSnapshotCount,
                priceRolloverCount,
                simulationClockService.currentMarketDateTime()
        );
        return cancelledOrderCount + priceRolloverCount + holdingSnapshotCount;
    }

    @Transactional
    public int cancelOpenOrderBookOrders(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        return cancelOpenOrderBookOrders(normalizedSymbol, simulationClockService.currentMarketDateTime());
    }

    private int cancelOpenOrderBookOrders(String symbol, LocalDateTime closedAt) {
        List<MarketCloseOrderRow> orders = writer.findOpenOrderBookOrdersForUpdate(symbol);
        for (MarketCloseOrderRow order : orders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                writer.creditCash(order.accountId(), order.reservedCash(), closedAt);
            }
            if (SELL.equals(order.side()) && order.remainingQuantity() > 0) {
                writer.releaseReservedSellQuantity(order.accountId(), order.symbol(), order.remainingQuantity(), closedAt);
            }
            writer.cancelOrder(order.id(), closedAt);
        }
        return orders.size();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase();
    }
}
