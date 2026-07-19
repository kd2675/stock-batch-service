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
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

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
    static final int MAX_NEW_ORDERS_PER_SIDE_PER_RUN = 10;

    private final ListingAutoAccountReader listingAutoAccountReader;
    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;

    int run(
            AutoMarketConfig config,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        int processed = 0;
        List<ListingAutoAccountConfig> listingConfigs = listingAutoAccountReader.findEnabledListingAutoAccountConfigs(config);
        if (listingConfigs.isEmpty()) {
            return 0;
        }
        LocalDateTime now = sessionApproval.businessEffectiveAt();
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
            PlannedListingOrders planned = planOrders(
                    listingConfig,
                    item.targets(),
                    orderBookState
            );
            if (planned.orders().isEmpty()) {
                continue;
            }
            AutoParticipantOrderGenerationResult result = autoMarketOrderExecutor.placeOrdersWithOpenFenceHeld(
                    planned.orders(),
                    sessionApproval
            );
            processed += result.generatedOrderCount();
            orderBookState = applyAcceptedOrders(orderBookState, planned.orders(), result);
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

    private PlannedListingOrders planOrders(
            ListingAutoAccountConfig config,
            QuoteTargets targets,
            AutoMarketOrderBookState orderBookState
    ) {
        List<AutoMarketPlannedOrder> orders = new ArrayList<>();
        List<SideDeficit> sideDeficits = orderSides(config).stream()
                .map(side -> new SideDeficit(
                        side,
                        Math.max(
                                0L,
                                targets.forSide(side)
                                        - autoMarketOrderReader.getOpenOrderQuantity(
                                                config.accountId(),
                                                config.symbol(),
                                                side
                                        )
                        )
                ))
                .toList();
        BigDecimal remainingCash = sideDeficits.stream().anyMatch(deficit -> deficit.isFor(BUY))
                ? listingAutoAccountReader.getCashBalance(config.accountId())
                : BigDecimal.ZERO;
        long remainingSellQuantity = sideDeficits.stream().anyMatch(deficit -> deficit.isFor(SELL))
                ? listingAutoAccountReader.getAvailableQuantity(config.accountId(), config.symbol())
                : 0L;

        for (SideDeficit sideDeficit : sideDeficits) {
            String side = sideDeficit.side();
            long remainingDeficit = sideDeficit.quantity();
            for (int orderIndex = 0;
                 orderIndex < MAX_NEW_ORDERS_PER_SIDE_PER_RUN && remainingDeficit > 0L;
                 orderIndex++) {
                BigDecimal price = orderPrice(
                        config,
                        side,
                        orderBookState
                );
                if (price == null) {
                    break;
                }
                long quantity = orderQuantity(
                        config,
                        side,
                        price,
                        remainingCash,
                        remainingSellQuantity
                );
                quantity = Math.min(remainingDeficit, quantity);
                if (quantity <= 0L) {
                    break;
                }
                AutoMarketPlannedOrder order = new AutoMarketPlannedOrder(
                        config.accountId(),
                        config.symbol(),
                        side,
                        price,
                        quantity
                );
                orders.add(order);
                remainingDeficit -= quantity;
                if (BUY.equals(side)) {
                    remainingCash = remainingCash.subtract(order.reservedCash());
                } else {
                    remainingSellQuantity -= quantity;
                }
            }
        }
        return new PlannedListingOrders(orders);
    }

    private AutoMarketOrderBookState applyAcceptedOrders(
            AutoMarketOrderBookState orderBookState,
            List<AutoMarketPlannedOrder> plannedOrders,
            AutoParticipantOrderGenerationResult result
    ) {
        AutoMarketOrderBookState updated = orderBookState;
        if (result.reservedBuyCount() > 0) {
            for (AutoMarketPlannedOrder order : plannedOrders) {
                if (BUY.equals(order.side())) {
                    updated = updated.withPlacedOrder(order.side(), order.price(), order.quantity());
                }
            }
        }
        if (result.reservedSellCount() > 0) {
            for (AutoMarketPlannedOrder order : plannedOrders) {
                if (SELL.equals(order.side())) {
                    updated = updated.withPlacedOrder(order.side(), order.price(), order.quantity());
                }
            }
        }
        return updated;
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
        long buyCapacity = Math.max(0L, targetHoldingQuantity - holdingQuantity);
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

    private long orderQuantity(
            ListingAutoAccountConfig config,
            String side,
            BigDecimal price,
            BigDecimal remainingCash,
            long remainingSellQuantity
    ) {
        long maxQuantity = Math.max(1, config.maxOrderQuantity());
        if (SELL.equals(side)) {
            return Math.min(maxQuantity, remainingSellQuantity);
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        long affordableQuantity = remainingCash.divide(price, 0, RoundingMode.DOWN).longValue();
        return Math.min(maxQuantity, affordableQuantity);
    }

    private BigDecimal orderPrice(
            ListingAutoAccountConfig config,
            String side,
            AutoMarketOrderBookState orderBookState
    ) {
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

    private record PlannedListingOrders(List<AutoMarketPlannedOrder> orders) {
    }

    private record SideDeficit(String side, long quantity) {

        private boolean isFor(String expectedSide) {
            return quantity > 0L && expectedSide.equals(side);
        }
    }

    private record QuoteTargets(long buyQuantity, long sellQuantity) {

        private static final QuoteTargets NONE = new QuoteTargets(0L, 0L);

        private long forSide(String side) {
            return BUY.equals(side) ? buyQuantity : sellQuantity;
        }
    }

    private record ListingConfigTargets(ListingAutoAccountConfig config, QuoteTargets targets) {
    }

}
