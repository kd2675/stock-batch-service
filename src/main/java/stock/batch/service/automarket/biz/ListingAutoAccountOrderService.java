package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private static final String TWO_SIDED = "TWO_SIDED";
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";
    private static final String RANDOM = "RANDOM";

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
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        List<AutoOrder> expiredOrders = new ArrayList<>();
        for (ListingAutoAccountConfig listingConfig : listingConfigs) {
            expiredOrders.addAll(findOldOrders(listingConfig, now));
        }
        processed += autoMarketOrderExecutor.expireOrders(expiredOrders, now);
        List<ListingConfigTargets> configTargets = listingConfigs.stream()
                .map(listingConfig -> new ListingConfigTargets(listingConfig, resolveQuoteTargets(listingConfig)))
                .toList();
        for (ListingConfigTargets item : configTargets) {
            processed += trimOrdersAboveTargets(item.config(), item.targets(), now);
        }
        AutoMarketOrderBookState orderBookState = autoMarketOrderExecutor.loadOrderBookState(config.symbol());
        for (ListingConfigTargets item : configTargets) {
            ListingAutoAccountConfig listingConfig = item.config();
            OwnOrderPrices ownOrderPrices = loadOwnOrderPrices(listingConfig);
            for (String side : orderSides(listingConfig)) {
                PlacedListingOrder placedOrder = placeOrder(
                        listingConfig,
                        side,
                        item.targets().forSide(side),
                        orderBookState,
                        ownOrderPrices
                );
                if (placedOrder != null) {
                    orderBookState = orderBookState.withPlacedOrder(
                            placedOrder.side(),
                            placedOrder.price(),
                            placedOrder.quantity()
                    );
                    ownOrderPrices = ownOrderPrices.withPlacedOrder(placedOrder.side(), placedOrder.price());
                    processed++;
                }
            }
        }
        return processed;
    }

    private List<AutoOrder> findOldOrders(ListingAutoAccountConfig config, LocalDateTime now) {
        LocalDateTime threshold = now.minusSeconds(Math.max(1, config.orderTtlSeconds()));
        return autoMarketOrderReader.findExpiredListingAutoOrders(config, threshold);
    }

    private int trimOrdersAboveTargets(
            ListingAutoAccountConfig config,
            QuoteTargets targets,
            LocalDateTime now
    ) {
        List<AutoOrder> excessOrders = new ArrayList<>();
        collectExcessOrders(config, BUY, targets.buyQuantity(), excessOrders);
        collectExcessOrders(config, SELL, targets.sellQuantity(), excessOrders);
        return autoMarketOrderExecutor.expireOrders(excessOrders, now);
    }

    private void collectExcessOrders(
            ListingAutoAccountConfig config,
            String side,
            long targetQuantity,
            List<AutoOrder> excessOrders
    ) {
        List<AutoOrder> openOrders = autoMarketOrderReader.findOpenListingAutoOrders(config, side);
        long openQuantity = openOrders.stream().mapToLong(AutoOrder::remainingQuantity).sum();
        for (AutoOrder order : openOrders) {
            if (openQuantity <= targetQuantity) {
                break;
            }
            excessOrders.add(order);
            openQuantity -= order.remainingQuantity();
        }
    }

    private PlacedListingOrder placeOrder(
            ListingAutoAccountConfig config,
            String side,
            long targetQuantity,
            AutoMarketOrderBookState orderBookState,
            OwnOrderPrices ownOrderPrices
    ) {
        long openQuantity = autoMarketOrderReader.getOpenOrderQuantity(config.accountId(), config.symbol(), side);
        long deficitQuantity = Math.max(0L, targetQuantity - openQuantity);
        if (deficitQuantity <= 0) {
            return null;
        }
        BigDecimal price = avoidSelfCross(
                config,
                side,
                orderPrice(config, side, orderBookState),
                ownOrderPrices
        );
        long quantity = Math.min(deficitQuantity, orderQuantity(config, side, price));
        if (quantity <= 0) {
            return null;
        }
        return autoMarketOrderExecutor.placeOrder(config.accountId(), config.symbol(), side, price, quantity)
                ? new PlacedListingOrder(side, price, quantity)
                : null;
    }

    private List<String> orderSides(ListingAutoAccountConfig config) {
        if (SELL_ONLY.equals(config.positionSide())) {
            return List.of(SELL);
        }
        if (BUY_ONLY.equals(config.positionSide())) {
            return List.of(BUY);
        }
        if (TWO_SIDED.equals(config.positionSide())) {
            return List.of(BUY, SELL);
        }
        return List.of();
    }

    private QuoteTargets resolveQuoteTargets(ListingAutoAccountConfig config) {
        if (!BUY_ONLY.equals(config.positionSide())
                && !SELL_ONLY.equals(config.positionSide())
                && !TWO_SIDED.equals(config.positionSide())) {
            return QuoteTargets.NONE;
        }
        long holdingQuantity = listingAutoAccountReader.getHoldingQuantity(config.accountId(), config.symbol());
        long targetHoldingQuantity = Math.max(0L, config.targetHoldingQuantity());
        if (TWO_SIDED.equals(config.positionSide()) && config.inventoryBandQuantity() > 0L) {
            long bandQuantity = config.inventoryBandQuantity();
            long lowerHoldingLimit = Math.max(0L, targetHoldingQuantity - bandQuantity);
            long upperHoldingLimit = saturatingAdd(targetHoldingQuantity, bandQuantity);
            return new QuoteTargets(
                    Math.min(Math.max(0L, config.targetBuyQuantity()), Math.max(0L, upperHoldingLimit - holdingQuantity)),
                    Math.min(Math.max(0L, config.targetSellQuantity()), Math.max(0L, holdingQuantity - lowerHoldingLimit))
            );
        }
        long buyCapacity = targetHoldingQuantity == 0L
                ? Long.MAX_VALUE
                : Math.max(0L, targetHoldingQuantity - holdingQuantity);
        long sellCapacity = Math.max(0L, holdingQuantity - targetHoldingQuantity);
        return new QuoteTargets(
                BUY_ONLY.equals(config.positionSide()) || TWO_SIDED.equals(config.positionSide())
                        ? Math.min(Math.max(0L, config.targetBuyQuantity()), buyCapacity)
                        : 0L,
                SELL_ONLY.equals(config.positionSide()) || TWO_SIDED.equals(config.positionSide())
                        ? Math.min(Math.max(0L, config.targetSellQuantity()), sellCapacity)
                        : 0L
        );
    }

    private long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
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
        int maxOffsetTicks = Math.max(0, config.priceOffsetTicks());
        int offsetTicks = maxOffsetTicks == 0 ? 0 : nextInt(1, maxOffsetTicks);
        BigDecimal bestBid = orderBookState.bestBid();
        BigDecimal bestAsk = orderBookState.bestAsk();
        BigDecimal anchorPrice = BUY.equals(side)
                ? (bestBid == null ? config.currentPrice() : bestBid)
                : (bestAsk == null ? config.currentPrice() : bestAsk);
        int direction = priceOffsetDirection(config, side);
        BigDecimal rawPrice = AutoMarketPricePolicy.moveByTicks(config.market(), anchorPrice, direction * offsetTicks);
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), rawPrice);
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

    private int priceOffsetDirection(ListingAutoAccountConfig config, String side) {
        String configuredDirection = BUY.equals(side)
                ? config.buyPriceOffsetDirection()
                : config.sellPriceOffsetDirection();
        if (UP.equals(configuredDirection)) {
            return 1;
        }
        if (DOWN.equals(configuredDirection)) {
            return -1;
        }
        if (RANDOM.equals(configuredDirection)) {
            return nextInt(0, 1) == 0 ? -1 : 1;
        }
        return BUY.equals(side) ? -1 : 1;
    }

    private OwnOrderPrices loadOwnOrderPrices(ListingAutoAccountConfig config) {
        return new OwnOrderPrices(
                autoMarketOrderReader.findBestPrice(config.accountId(), config.symbol(), BUY),
                autoMarketOrderReader.findBestPrice(config.accountId(), config.symbol(), SELL)
        );
    }

    private BigDecimal avoidSelfCross(
            ListingAutoAccountConfig config,
            String side,
            BigDecimal proposedPrice,
            OwnOrderPrices ownOrderPrices
    ) {
        BigDecimal adjustedPrice = proposedPrice;
        if (BUY.equals(side) && ownOrderPrices.bestAsk() != null && adjustedPrice.compareTo(ownOrderPrices.bestAsk()) >= 0) {
            adjustedPrice = AutoMarketPricePolicy.moveByTicks(config.market(), ownOrderPrices.bestAsk(), -1);
        }
        if (SELL.equals(side) && ownOrderPrices.bestBid() != null && adjustedPrice.compareTo(ownOrderPrices.bestBid()) <= 0) {
            adjustedPrice = AutoMarketPricePolicy.moveByTicks(config.market(), ownOrderPrices.bestBid(), 1);
        }
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), adjustedPrice);
        return normalizePriceWithinDailyLimit(adjustedPrice.max(tick), config, tick);
    }

    private record PlacedListingOrder(String side, BigDecimal price, long quantity) {
    }

    private record QuoteTargets(long buyQuantity, long sellQuantity) {

        private static final QuoteTargets NONE = new QuoteTargets(0L, 0L);

        private long forSide(String side) {
            return BUY.equals(side) ? buyQuantity : sellQuantity;
        }
    }

    private record ListingConfigTargets(ListingAutoAccountConfig config, QuoteTargets targets) {
    }

    private record OwnOrderPrices(BigDecimal bestBid, BigDecimal bestAsk) {

        private OwnOrderPrices withPlacedOrder(String side, BigDecimal price) {
            if (BUY.equals(side)) {
                return new OwnOrderPrices(bestBid == null || price.compareTo(bestBid) > 0 ? price : bestBid, bestAsk);
            }
            return new OwnOrderPrices(bestBid, bestAsk == null || price.compareTo(bestAsk) < 0 ? price : bestAsk);
        }
    }
}
