package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.reader.ListingAutoAccountReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.normalizePriceWithinDailyLimit;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.nextInt;

@Component
@RequiredArgsConstructor
class ListingAutoAccountOrderService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String SELL_ONLY = "SELL_ONLY";
    private static final String BUY_ONLY = "BUY_ONLY";

    private final ListingAutoAccountReader listingAutoAccountReader;
    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final SimulationClockService simulationClockService;

    int run(AutoMarketConfig config) {
        int processed = 0;
        List<ListingAutoAccountConfig> listingConfigs = listingAutoAccountReader.findEnabledListingAutoAccountConfigs(config);
        if (listingConfigs.isEmpty()) {
            return 0;
        }
        for (ListingAutoAccountConfig listingConfig : listingConfigs) {
            processed += expireOldOrders(listingConfig);
        }
        AutoMarketOrderBookState orderBookState = autoMarketOrderExecutor.loadOrderBookState(config.symbol());
        for (ListingAutoAccountConfig listingConfig : listingConfigs) {
            PlacedListingOrder placedOrder = placeOrder(listingConfig, orderBookState);
            if (placedOrder != null) {
                orderBookState = orderBookState.withPlacedOrder(placedOrder.side(), placedOrder.price(), 0);
                processed++;
            }
        }
        return processed;
    }

    private int expireOldOrders(ListingAutoAccountConfig config) {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        LocalDateTime threshold = now.minusSeconds(Math.max(1, config.orderTtlSeconds()));
        List<AutoOrder> orders = autoMarketOrderReader.findExpiredListingAutoOrders(config, threshold);

        int expired = 0;
        for (AutoOrder order : orders) {
            autoMarketOrderExecutor.expireOrder(order, now);
            expired++;
        }
        return expired;
    }

    private PlacedListingOrder placeOrder(ListingAutoAccountConfig config, AutoMarketOrderBookState orderBookState) {
        String side = orderSide(config);
        if (side == null || autoMarketOrderReader.getOpenOrderQuantity(config.accountId(), config.symbol(), side) > 0) {
            return null;
        }
        BigDecimal price = orderPrice(config, side, orderBookState);
        long quantity = orderQuantity(config, side, price);
        if (quantity <= 0) {
            return null;
        }
        return autoMarketOrderExecutor.placeOrder(config.accountId(), config.symbol(), side, price, quantity)
                ? new PlacedListingOrder(side, price)
                : null;
    }

    private String orderSide(ListingAutoAccountConfig config) {
        if (SELL_ONLY.equals(config.positionSide())) {
            return SELL;
        }
        if (BUY_ONLY.equals(config.positionSide())) {
            return BUY;
        }
        return null;
    }

    private long orderQuantity(ListingAutoAccountConfig config, String side, BigDecimal price) {
        long maxQuantity = Math.max(1, config.maxOrderQuantity());
        if (SELL.equals(side)) {
            long availableQuantity = listingAutoAccountReader.getAvailableQuantity(config.accountId(), config.symbol());
            return Math.min(maxQuantity, availableQuantity);
        }
        BigDecimal cashBalance = listingAutoAccountReader.getCashBalance(config.accountId());
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        long affordableQuantity = cashBalance.divide(price, 0, RoundingMode.DOWN).longValue();
        return Math.min(maxQuantity, affordableQuantity);
    }

    private BigDecimal orderPrice(ListingAutoAccountConfig config, String side, AutoMarketOrderBookState orderBookState) {
        int offsetTicks = nextInt(0, Math.max(0, config.priceOffsetTicks()));
        BigDecimal bestBid = orderBookState.bestBid();
        BigDecimal bestAsk = orderBookState.bestAsk();
        BigDecimal rawPrice;
        if (SELL.equals(side)) {
            rawPrice = AutoMarketPricePolicy.moveByTicks(config.market(), bestAsk == null ? config.currentPrice() : bestAsk, offsetTicks);
            if (bestBid != null && rawPrice.compareTo(bestBid) <= 0) {
                rawPrice = AutoMarketPricePolicy.moveByTicks(config.market(), bestBid, 1);
            }
        } else {
            rawPrice = AutoMarketPricePolicy.moveByTicks(config.market(), bestBid == null ? config.currentPrice() : bestBid, -offsetTicks);
            if (bestAsk != null && rawPrice.compareTo(bestAsk) >= 0) {
                rawPrice = AutoMarketPricePolicy.moveByTicks(config.market(), bestAsk, -1);
            }
        }
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), rawPrice);
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

    private record PlacedListingOrder(String side, BigDecimal price) {
    }
}
