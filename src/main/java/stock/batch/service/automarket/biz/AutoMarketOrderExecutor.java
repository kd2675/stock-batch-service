package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.simulation.SimulationClockService;

@Component
@RequiredArgsConstructor
class AutoMarketOrderExecutor {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketWriter autoMarketWriter;
    private final SimulationClockService simulationClockService;

    AutoMarketOrderBookState loadOrderBookState(String symbol) {
        return new AutoMarketOrderBookState(
                autoMarketOrderReader.findBestPrice(symbol, BUY),
                autoMarketOrderReader.findBestPrice(symbol, SELL),
                autoMarketOrderReader.getOpenOrderQuantity(symbol, BUY),
                autoMarketOrderReader.getOpenOrderQuantity(symbol, SELL)
        );
    }

    void expireOrder(AutoOrder order, LocalDateTime now) {
        if (!autoMarketWriter.cancelOpenOrder(order, now)) {
            return;
        }
        if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
            autoMarketWriter.creditCash(order.accountId(), order.reservedCash(), now);
        }
        if (SELL.equals(order.side())) {
            autoMarketWriter.releaseReservedSellQuantity(order, now);
        }
    }

    boolean placeOrder(long accountId, String symbol, String side, BigDecimal price, long quantity) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        BigDecimal reservedCash = BigDecimal.ZERO;
        if (BUY.equals(side)) {
            reservedCash = price.multiply(BigDecimal.valueOf(quantity));
            if (!autoMarketWriter.reserveBuyCash(accountId, reservedCash, now)) {
                return false;
            }
        } else {
            if (!autoMarketWriter.reserveSellQuantity(accountId, symbol, quantity, now)) {
                return false;
            }
        }

        boolean inserted = autoMarketWriter.insertLimitOrder(
                nextClientOrderId(),
                accountId,
                symbol,
                side,
                price,
                quantity,
                reservedCash,
                now
        );
        if (!inserted) {
            throw new IllegalStateException("Order book market is not open for auto order: symbol=" + symbol);
        }
        return true;
    }

    private String nextClientOrderId() {
        return "auto-" + UUID.randomUUID().toString().replace("-", "");
    }
}
