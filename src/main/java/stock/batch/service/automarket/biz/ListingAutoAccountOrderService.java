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
@Component
@RequiredArgsConstructor
class ListingAutoAccountOrderService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String SELL_ONLY = "SELL_ONLY";
    private static final String BUY_ONLY = "BUY_ONLY";
    private static final String TWO_SIDED = "TWO_SIDED";
    private static final String UNDERWRITER_RETURN = "UNDERWRITER_RETURN";
    private static final String HYBRID = "HYBRID";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
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
        List<ListingConfigTargets> configTargets = listingConfigs.stream().map(listingConfig -> {
            var inventory = listingAutoAccountReader.getInventoryState(
                    listingConfig.accountId(), listingConfig.symbol()
            );
            return new ListingConfigTargets(
                    listingConfig,
                    resolveQuoteTargets(listingConfig, inventory.holdingQuantity()),
                    inventory
            );
        }).toList();
        for (ListingConfigTargets item : configTargets) {
            processed += trimOrdersAboveTargets(item.config(), item.targets(), now);
        }
        for (ListingConfigTargets item : configTargets) {
            ListingAutoAccountConfig listingConfig = item.config();
            AutoMarketOrderBookState orderBookState = autoMarketOrderExecutor.loadExternalOrderBookState(
                    config.symbol(), listingConfig.accountId()
            );
            PlannedListingOrders planned = planOrders(
                    listingConfig,
                    item.targets(),
                    item.inventory(),
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
            ListingAutoAccountReader.ListingAutoInventoryState inventory,
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
                ? inventory.cashBalance()
                : BigDecimal.ZERO;
        long remainingSellQuantity = sideDeficits.stream().anyMatch(deficit -> deficit.isFor(SELL))
                ? inventory.availableQuantity()
                : 0L;

        for (SideDeficit sideDeficit : sideDeficits) {
            String side = sideDeficit.side();
            long remainingDeficit = sideDeficit.quantity();
            long aggressiveBudget = aggressiveBudget(config, inventory, side, remainingDeficit);
            for (int orderIndex = 0;
                 orderIndex < MAX_NEW_ORDERS_PER_SIDE_PER_RUN && remainingDeficit > 0L;
                 orderIndex++) {
                BigDecimal price = orderPrice(
                        config,
                        side,
                        orderBookState,
                        inventory,
                        orderIndex,
                        aggressiveBudget > 0L
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
                if (aggressiveBudget > 0L) {
                    quantity = Math.min(quantity, aggressiveBudget);
                }
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
                aggressiveBudget = Math.max(0L, aggressiveBudget - quantity);
                if (BUY.equals(side)) {
                    remainingCash = remainingCash.subtract(order.reservedCash());
                } else {
                    remainingSellQuantity -= quantity;
                }
            }
        }
        return new PlannedListingOrders(orders);
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

    private QuoteTargets resolveQuoteTargets(ListingAutoAccountConfig config, long holdingQuantity) {
        if (!BUY_ONLY.equals(config.positionSide())
                && !SELL_ONLY.equals(config.positionSide())
                && !TWO_SIDED.equals(config.positionSide())) {
            return QuoteTargets.NONE;
        }
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
            AutoMarketOrderBookState orderBookState,
            ListingAutoAccountReader.ListingAutoInventoryState inventory,
            int orderIndex,
            boolean aggressive
    ) {
        BigDecimal bestBid = orderBookState.bestBid();
        BigDecimal bestAsk = orderBookState.bestAsk();
        if (aggressive) {
            BigDecimal oppositePrice = BUY.equals(side) ? bestAsk : bestBid;
            if (oppositePrice != null) {
                return normalizePriceWithinDailyLimit(
                        oppositePrice,
                        config,
                        KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), oppositePrice)
                );
            }
        }
        BigDecimal referencePrice = referencePrice(config, bestBid, bestAsk);
        BigDecimal reservationPrice = inventoryAdjustedReference(config, inventory, referencePrice);
        int targetSpreadTicks = Math.max(1, config.targetSpreadTicks());
        int baseDistance = BUY.equals(side) ? targetSpreadTicks / 2 : targetSpreadTicks - targetSpreadTicks / 2;
        int ladderDepth = Math.max(0, config.priceOffsetTicks());
        int ladderOffset = ladderDepth == 0 ? 0 : orderIndex % (ladderDepth + 1);
        int direction = BUY.equals(side) ? -1 : 1;
        BigDecimal rawPrice = AutoMarketPricePolicy.moveByTicks(
                config.market(), reservationPrice, direction * (baseDistance + ladderOffset)
        );
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), rawPrice);
        rawPrice = keepPassive(side, rawPrice, bestBid, bestAsk, tick);
        rawPrice = applyProfitFloor(config, side, inventory, rawPrice, aggressive);
        if (rawPrice == null) {
            return null;
        }
        BigDecimal normalized = normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
        return violatesProfitFloor(config, side, inventory, normalized, aggressive) ? null : normalized;
    }

    private BigDecimal referencePrice(ListingAutoAccountConfig config, BigDecimal bestBid, BigDecimal bestAsk) {
        if (bestBid != null && bestAsk != null && bestAsk.compareTo(bestBid) > 0) {
            return bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        if (bestBid != null) {
            return bestBid;
        }
        if (bestAsk != null) {
            return bestAsk;
        }
        return config.currentPrice();
    }

    private BigDecimal inventoryAdjustedReference(
            ListingAutoAccountConfig config,
            ListingAutoAccountReader.ListingAutoInventoryState inventory,
            BigDecimal referencePrice
    ) {
        long band = Math.max(1L, config.inventoryBandQuantity());
        double deviation = Math.clamp(
                (double) (inventory.holdingQuantity() - config.targetHoldingQuantity()) / band,
                -1.0,
                1.0
        );
        int skewTicks = (int) Math.round(-deviation * Math.max(0, config.inventorySkewTicks()));
        return AutoMarketPricePolicy.moveByTicks(config.market(), referencePrice, skewTicks);
    }

    private BigDecimal keepPassive(
            String side,
            BigDecimal price,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            BigDecimal tick
    ) {
        if (BUY.equals(side) && bestAsk != null && price.compareTo(bestAsk) >= 0) {
            return bestAsk.subtract(tick).max(tick);
        }
        if (SELL.equals(side) && bestBid != null && price.compareTo(bestBid) <= 0) {
            return bestBid.add(tick);
        }
        return price;
    }

    private BigDecimal applyProfitFloor(
            ListingAutoAccountConfig config,
            String side,
            ListingAutoAccountReader.ListingAutoInventoryState inventory,
            BigDecimal price,
            boolean aggressive
    ) {
        if (!requiresProfitFloor(config, side, aggressive) || inventory.averagePrice().signum() <= 0) {
            return price;
        }
        BigDecimal profitMultiplier = BigDecimal.ONE.add(
                config.minimumProfitRate().max(BigDecimal.ZERO).divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP)
        );
        return price.max(inventory.averagePrice().multiply(profitMultiplier));
    }

    private boolean violatesProfitFloor(
            ListingAutoAccountConfig config,
            String side,
            ListingAutoAccountReader.ListingAutoInventoryState inventory,
            BigDecimal price,
            boolean aggressive
    ) {
        if (!requiresProfitFloor(config, side, aggressive) || inventory.averagePrice().signum() <= 0) {
            return false;
        }
        BigDecimal floor = inventory.averagePrice().multiply(BigDecimal.ONE.add(
                config.minimumProfitRate().max(BigDecimal.ZERO).divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP)
        ));
        return price.compareTo(floor) < 0;
    }

    private boolean requiresProfitFloor(ListingAutoAccountConfig config, String side, boolean aggressive) {
        if (!SELL.equals(side) || aggressive) {
            return false;
        }
        return UNDERWRITER_RETURN.equals(config.operationMode()) || HYBRID.equals(config.operationMode());
    }

    private long aggressiveBudget(
            ListingAutoAccountConfig config,
            ListingAutoAccountReader.ListingAutoInventoryState inventory,
            String side,
            long deficit
    ) {
        long band = Math.max(1L, config.inventoryBandQuantity());
        long deviation = inventory.holdingQuantity() - config.targetHoldingQuantity();
        boolean correctiveSide = deviation > 0L ? SELL.equals(side) : deviation < 0L && BUY.equals(side);
        if (!correctiveSide) {
            return 0L;
        }
        double deviationRatio = Math.min(1.0, Math.abs((double) deviation) / band);
        double threshold = config.aggressiveUnwindThreshold().doubleValue();
        double ratio = config.aggressiveOrderRatio().doubleValue();
        if (ratio <= 0.0 || deviationRatio < threshold) {
            return 0L;
        }
        return Math.min(deficit, Math.max(1L, (long) Math.ceil(deficit * Math.min(1.0, ratio))));
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

    private record ListingConfigTargets(
            ListingAutoAccountConfig config,
            QuoteTargets targets,
            ListingAutoAccountReader.ListingAutoInventoryState inventory
    ) {
    }

}
