package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.StockOrderOriginType;

@Component
class LiquidityProviderQuotePlanner {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String QUOTING = "QUOTING";
    private static final String EXEMPT = "EXEMPT";
    private static final String HALTED = "HALTED";

    LiquidityProviderQuotePlan plan(LiquidityProviderQuoteInput input) {
        LiquidityProviderMandate mandate = input.mandate();
        AutoMarketConfig config = input.marketConfig();
        PressureSnapshot pressures = pressures(mandate, config);
        long executionLimit = quantityLimit(
                mandate.referenceDailyVolume(),
                mandate.dailyExecutionParticipationRate()
        );
        long submissionLimit = multipliedLimit(executionLimit, mandate.dailySubmissionMultiplier());
        BigDecimal unrealizedProfit = input.account().unrealizedProfit(config.currentPrice());
        List<AutoOrder> allOpenOrders = sortedOpenOrders(input.openOrders());
        BigDecimal currentNetAssetValue = currentNetAssetValue(input, allOpenOrders);
        BigDecimal openingNetAssetValue = input.dailyState().exists()
                ? input.dailyState().openingNetAssetValue()
                : currentNetAssetValue;
        BigDecimal riskProfit = currentNetAssetValue
                .subtract(openingNetAssetValue)
                .setScale(2, RoundingMode.HALF_UP);
        NetAssetSnapshot netAssets = new NetAssetSnapshot(
                openingNetAssetValue,
                currentNetAssetValue,
                riskProfit
        );

        LiquidityProviderQuotePlan terminal = terminalPlanIfRequired(
                input,
                pressures,
                executionLimit,
                submissionLimit,
                unrealizedProfit,
                netAssets,
                allOpenOrders
        );
        if (terminal != null) {
            return terminal;
        }

        QuotePrices prices = quotePrices(input, pressures);
        if (prices == null) {
            return terminalPlan(
                    input,
                    HALTED,
                    "INVALID_QUOTE_PRICE",
                    true,
                    pressures,
                    executionLimit,
                    submissionLimit,
                    unrealizedProfit,
                    netAssets,
                    allOpenOrders
            );
        }

        QuoteTargets targets = quoteTargets(input, pressures);
        List<AutoOrder> cancellations = new ArrayList<>();
        SideRetention buyRetention = retainOneSide(
                sideOrders(allOpenOrders, BUY),
                BUY,
                prices.bidPrice(),
                targets.buyQuantity(),
                input
        );
        SideRetention sellRetention = retainOneSide(
                sideOrders(allOpenOrders, SELL),
                SELL,
                prices.askPrice(),
                targets.sellQuantity(),
                input
        );
        cancellations.addAll(buyRetention.cancellations());
        cancellations.addAll(sellRetention.cancellations());
        if (retainedQuotesCross(buyRetention, sellRetention, prices)) {
            if (buyRetention.retainedOrder() != null) {
                cancellations.add(buyRetention.retainedOrder());
                buyRetention = SideRetention.NONE;
            }
            if (sellRetention.retainedOrder() != null) {
                cancellations.add(sellRetention.retainedOrder());
                sellRetention = SideRetention.NONE;
            }
        }

        long retainedBuyQuantity = buyRetention.retainedQuantity();
        long retainedSellQuantity = sellRetention.retainedQuantity();
        long retainedGrossQuantity = saturatingAdd(retainedBuyQuantity, retainedSellQuantity);
        long remainingExecutionQuantity = nonNegativeSubtract(
                executionLimit,
                saturatingAdd(input.executions().grossQuantity(), retainedGrossQuantity)
        );
        long remainingSubmissionQuantity = nonNegativeSubtract(
                submissionLimit,
                input.dailyState().grossSubmittedQuantity()
        );
        long sharedNewOrderBudget = Math.min(remainingExecutionQuantity, remainingSubmissionQuantity);

        BigDecimal releasedBuyCash = cancellations.stream()
                .filter(order -> BUY.equals(order.side()))
                .map(AutoOrder::reservedCash)
                .filter(amount -> amount != null && amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long releasedSellQuantity = cancellations.stream()
                .filter(order -> SELL.equals(order.side()))
                .mapToLong(AutoOrder::remainingQuantity)
                .sum();
        BigDecimal availableCashAfterCancellation = input.account().availableCash().add(releasedBuyCash);
        long availableSellAfterCancellation = saturatingAdd(
                input.account().availableQuantity(),
                releasedSellQuantity
        );

        long lowerInventoryLimit = Math.max(
                0L,
                mandate.targetInventoryQuantity() - mandate.inventoryBandQuantity()
        );
        long upperInventoryLimit = saturatingAdd(
                mandate.targetInventoryQuantity(),
                mandate.inventoryBandQuantity()
        );
        long buyBandCapacity = nonNegativeSubtract(
                upperInventoryLimit,
                saturatingAdd(input.account().holdingQuantity(), retainedBuyQuantity)
        );
        long sellBandCapacity = nonNegativeSubtract(
                input.account().holdingQuantity(),
                saturatingAdd(lowerInventoryLimit, retainedSellQuantity)
        );

        long buyCandidate = retainedBuyQuantity > 0L
                ? 0L
                : Math.min(
                        targets.buyQuantity(),
                        minPositiveOrZero(
                                targets.buySingleOrderLimit(),
                                buyBandCapacity,
                                affordableQuantity(availableCashAfterCancellation, prices.bidPrice())
                        )
                );
        long sellCandidate = retainedSellQuantity > 0L
                ? 0L
                : Math.min(
                        targets.sellQuantity(),
                        minPositiveOrZero(
                                targets.sellSingleOrderLimit(),
                                sellBandCapacity,
                                availableSellAfterCancellation
                        )
                );

        SideAllocations allocations = allocateSharedBudget(
                buyCandidate,
                sellCandidate,
                sharedNewOrderBudget,
                input.account().holdingQuantity() - mandate.targetInventoryQuantity()
        );
        List<AutoMarketPlannedOrder> proposedOrders = new ArrayList<>(2);
        appendProposedOrder(
                proposedOrders,
                input,
                BUY,
                prices.bidPrice(),
                allocations.buyQuantity()
        );
        appendProposedOrder(
                proposedOrders,
                input,
                SELL,
                prices.askPrice(),
                allocations.sellQuantity()
        );

        long resultingBuyQuantity = saturatingAdd(
                retainedBuyQuantity,
                proposedQuantity(proposedOrders, BUY)
        );
        long resultingSellQuantity = saturatingAdd(
                retainedSellQuantity,
                proposedQuantity(proposedOrders, SELL)
        );
        long projectedInventory = signedProjectedInventory(
                input.account().holdingQuantity(),
                resultingBuyQuantity,
                resultingSellQuantity
        );
        String gateReason = resolveGateReason(
                input,
                targets,
                retainedGrossQuantity,
                proposedOrders,
                remainingExecutionQuantity,
                remainingSubmissionQuantity,
                buyCandidate,
                sellCandidate
        );

        return new LiquidityProviderQuotePlan(
                QUOTING,
                gateReason,
                false,
                true,
                cancellations,
                proposedOrders,
                mandate.referenceDailyVolume(),
                executionLimit,
                submissionLimit,
                targets.buyQuantity(),
                targets.sellQuantity(),
                retainedBuyQuantity,
                retainedSellQuantity,
                prices.bidPrice(),
                prices.askPrice(),
                input.account().holdingQuantity(),
                projectedInventory,
                input.externalBook().buyDepthQuantity(),
                input.externalBook().sellDepthQuantity(),
                pressures.price(),
                pressures.volatility(),
                pressures.liquidity(),
                unrealizedProfit,
                netAssets.opening(),
                netAssets.current(),
                netAssets.profit()
        );
    }

    private LiquidityProviderQuotePlan terminalPlanIfRequired(
            LiquidityProviderQuoteInput input,
            PressureSnapshot pressures,
            long executionLimit,
            long submissionLimit,
            BigDecimal unrealizedProfit,
            NetAssetSnapshot netAssets,
            List<AutoOrder> allOpenOrders
    ) {
        LiquidityProviderMandate mandate = input.mandate();
        if (!mandate.symbol().equals(input.marketConfig().symbol())) {
            return terminalPlan(
                    input, HALTED, "SYMBOL_MISMATCH", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (!"ACTIVE".equals(mandate.status()) || !mandate.contractActiveOn(input.simulationTradeDate())) {
            return terminalPlan(
                    input, EXEMPT, "CONTRACT_NOT_ACTIVE", false, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (!input.marketTradingEnabled()) {
            return terminalPlan(
                    input, EXEMPT, "MARKET_NOT_AVAILABLE", false, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (!mandate.live()) {
            return terminalPlan(
                    input, HALTED, "INVALID_EXECUTION_MODE", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (input.enabledLegacyLiquidityConfigCount() > 0) {
            return terminalPlan(
                    input, HALTED, "LEGACY_LIQUIDITY_ENGINE_ACTIVE", true, pressures,
                    executionLimit, submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        String roleFailure = input.account().eligibilityFailure(mandate, input.simulationTradeDate());
        if (roleFailure != null) {
            return terminalPlan(
                    input, HALTED, roleFailure, true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (!mandate.passiveOnly()) {
            return terminalPlan(
                    input, HALTED, "NON_PASSIVE_POLICY_NOT_SUPPORTED", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (input.dailyState().exists() && input.dailyState().limitBreached()) {
            return terminalPlan(
                    input, HALTED, "PREVIOUS_HARD_LIMIT_BREACH", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (input.dailyState().exists()
                && (input.dailyState().policyVersion() != mandate.policyVersion()
                || input.dailyState().referenceDailyVolume() != mandate.referenceDailyVolume()
                || input.dailyState().executionQuantityLimit() != executionLimit
                || input.dailyState().submissionQuantityLimit() != submissionLimit)) {
            return terminalPlan(
                    input, HALTED, "POLICY_CHANGED_DURING_SESSION", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (!input.dailyState().exists()
                && (input.executions().grossQuantity() > 0L || !allOpenOrders.isEmpty())) {
            return terminalPlan(
                    input, HALTED, "DAILY_STATE_INCONSISTENT", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (input.openOrderOverflow()) {
            return terminalPlan(
                    input, HALTED, "OPEN_ORDER_SCAN_LIMIT_EXCEEDED", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (input.externalBook().crossed()) {
            return terminalPlan(
                    input, HALTED, "EXTERNAL_BOOK_CROSSED", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (!validMarketPrice(input.marketConfig().currentPrice())) {
            return terminalPlan(
                    input, HALTED, "INVALID_CURRENT_PRICE", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (input.dailyState().grossSubmittedQuantity() > submissionLimit) {
            return terminalPlan(
                    input, HALTED, "SUBMISSION_LIMIT_BREACHED", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        long openQuantity = allOpenOrders.stream()
                .map(AutoOrder::remainingQuantity)
                .reduce(0L, this::saturatingAdd);
        if (input.executions().grossQuantity() >= executionLimit) {
            return terminalPlan(
                    input, HALTED, "EXECUTION_LIMIT_REACHED", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        if (saturatingAdd(input.executions().grossQuantity(), openQuantity) > executionLimit) {
            return terminalPlan(
                    input, HALTED, "OPEN_EXPOSURE_OVER_EXECUTION_LIMIT", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        BigDecimal negativeLossLimit = input.mandate().dailyLossLimitAmount().negate();
        if (netAssets.profit().compareTo(negativeLossLimit) <= 0) {
            return terminalPlan(
                    input, HALTED, "LOSS_LIMIT_REACHED", true, pressures, executionLimit,
                    submissionLimit, unrealizedProfit, netAssets, allOpenOrders
            );
        }
        return null;
    }

    private LiquidityProviderQuotePlan terminalPlan(
            LiquidityProviderQuoteInput input,
            String stateStatus,
            String gateReason,
            boolean limitBreached,
            PressureSnapshot pressures,
            long executionLimit,
            long submissionLimit,
            BigDecimal unrealizedProfit,
            NetAssetSnapshot netAssets,
            List<AutoOrder> allOpenOrders
    ) {
        return new LiquidityProviderQuotePlan(
                stateStatus,
                gateReason,
                limitBreached,
                false,
                allOpenOrders,
                List.of(),
                input.mandate().referenceDailyVolume(),
                executionLimit,
                submissionLimit,
                0L,
                0L,
                0L,
                0L,
                null,
                null,
                input.account().holdingQuantity(),
                input.account().holdingQuantity(),
                input.externalBook().buyDepthQuantity(),
                input.externalBook().sellDepthQuantity(),
                pressures.price(),
                pressures.volatility(),
                pressures.liquidity(),
                unrealizedProfit,
                netAssets.opening(),
                netAssets.current(),
                netAssets.profit()
        );
    }

    private QuoteTargets quoteTargets(
            LiquidityProviderQuoteInput input,
            PressureSnapshot pressures
    ) {
        LiquidityProviderMandate mandate = input.mandate();
        long maxOpenQuantity = quantityLimit(
                mandate.referenceDailyVolume(),
                mandate.maxOpenParticipationRate()
        );
        double liquidityMultiplier = Math.clamp(
                1.0 + pressures.liquidity() * mandate.liquiditySizeSensitivity().doubleValue(),
                0.5,
                1.5
        );
        long baseTarget = Math.min(
                maxOpenQuantity,
                scaledQuantity(
                        mandate.referenceDailyVolume(),
                        mandate.targetOpenParticipationRate(),
                        liquidityMultiplier
                )
        );
        double inventoryDeviation = Math.clamp(
                (double) (input.account().holdingQuantity() - mandate.targetInventoryQuantity())
                        / Math.max(1L, mandate.inventoryBandQuantity()),
                -1.0,
                1.0
        );
        long rawBuyTarget = scaledQuantity(baseTarget, 1.0 - inventoryDeviation * 0.5);
        long rawSellTarget = scaledQuantity(baseTarget, 1.0 + inventoryDeviation * 0.5);
        long lowerInventoryLimit = Math.max(
                0L,
                mandate.targetInventoryQuantity() - mandate.inventoryBandQuantity()
        );
        long upperInventoryLimit = saturatingAdd(
                mandate.targetInventoryQuantity(),
                mandate.inventoryBandQuantity()
        );
        long buyTarget = Math.min(
                Math.min(rawBuyTarget, maxOpenQuantity),
                nonNegativeSubtract(upperInventoryLimit, input.account().holdingQuantity())
        );
        long sellTarget = Math.min(
                Math.min(rawSellTarget, maxOpenQuantity),
                nonNegativeSubtract(input.account().holdingQuantity(), lowerInventoryLimit)
        );
        long referenceSingleOrderLimit = Math.min(
                mandate.maxOrderQuantity(),
                quantityLimit(
                        mandate.referenceDailyVolume(),
                        mandate.maxSingleOrderParticipationRate()
                )
        );
        long buyDepthLimit = externalDepthLimit(
                input.externalBook().buyDepthQuantity(),
                mandate.maxExternalDepthParticipationRate()
        );
        long sellDepthLimit = externalDepthLimit(
                input.externalBook().sellDepthQuantity(),
                mandate.maxExternalDepthParticipationRate()
        );
        long buySingleOrderLimit = applyExternalDepthLimit(referenceSingleOrderLimit, buyDepthLimit);
        long sellSingleOrderLimit = applyExternalDepthLimit(referenceSingleOrderLimit, sellDepthLimit);
        return new QuoteTargets(
                Math.min(buyTarget, buySingleOrderLimit),
                Math.min(sellTarget, sellSingleOrderLimit),
                buySingleOrderLimit,
                sellSingleOrderLimit
        );
    }

    private QuotePrices quotePrices(
            LiquidityProviderQuoteInput input,
            PressureSnapshot pressures
    ) {
        LiquidityProviderMandate mandate = input.mandate();
        AutoMarketConfig config = input.marketConfig();
        int volatilityExtraTicks = (int) Math.round(
                Math.max(0.0, pressures.volatility()) * mandate.volatilitySpreadMaxTicks()
        );
        int spreadTicks = Math.clamp(
                mandate.targetSpreadTicks() + volatilityExtraTicks,
                mandate.targetSpreadTicks(),
                mandate.maxSpreadTicks()
        );
        double inventoryDeviation = Math.clamp(
                (double) (input.account().holdingQuantity() - mandate.targetInventoryQuantity())
                        / Math.max(1L, mandate.inventoryBandQuantity()),
                -1.0,
                1.0
        );
        int inventoryShiftTicks = (int) Math.round(-inventoryDeviation * mandate.inventorySkewTicks());
        int priceShiftTicks = (int) Math.round(
                pressures.price() * mandate.priceRegimeMaxSkewTicks()
        );
        int centerShiftTicks = Math.clamp(
                inventoryShiftTicks + priceShiftTicks,
                -(mandate.inventorySkewTicks() + mandate.priceRegimeMaxSkewTicks()),
                mandate.inventorySkewTicks() + mandate.priceRegimeMaxSkewTicks()
        );
        BigDecimal center = AutoMarketPricePolicy.normalizePriceWithinDailyLimit(
                config.currentPrice(),
                config,
                config.tickSize()
        );
        center = normalizeWithinLimits(
                AutoMarketPricePolicy.moveByTicks(config.market(), center, centerShiftTicks),
                config
        );
        int bidDistance = spreadTicks / 2;
        int askDistance = spreadTicks - bidDistance;
        BigDecimal bid = normalizeWithinLimits(
                AutoMarketPricePolicy.moveByTicks(config.market(), center, -bidDistance),
                config
        );
        BigDecimal ask = normalizeWithinLimits(
                AutoMarketPricePolicy.moveByTicks(config.market(), center, askDistance),
                config
        );

        LiquidityProviderExternalBook external = input.externalBook();
        if (external.bestBid() != null) {
            BigDecimal externalBid = normalizeWithinLimits(external.bestBid(), config);
            bid = bid.max(externalBid);
            BigDecimal minimumPassiveAsk = normalizeWithinLimits(
                    AutoMarketPricePolicy.moveByTicks(config.market(), externalBid, 1),
                    config
            );
            ask = ask.max(minimumPassiveAsk);
        }
        if (external.bestAsk() != null) {
            BigDecimal externalAsk = normalizeWithinLimits(external.bestAsk(), config);
            ask = ask.min(externalAsk);
            BigDecimal maximumPassiveBid = normalizeWithinLimits(
                    AutoMarketPricePolicy.moveByTicks(config.market(), externalAsk, -1),
                    config
            );
            bid = bid.min(maximumPassiveBid);
        }
        bid = normalizeWithinLimits(bid, config);
        ask = normalizeWithinLimits(ask, config);
        if (bid.compareTo(ask) >= 0) {
            BigDecimal repairedBid = normalizeWithinLimits(
                    AutoMarketPricePolicy.moveByTicks(config.market(), ask, -1),
                    config
            );
            if (repairedBid.compareTo(ask) >= 0) {
                return null;
            }
            bid = repairedBid;
        }
        return new QuotePrices(bid, ask);
    }

    private SideRetention retainOneSide(
            List<AutoOrder> sideOrders,
            String side,
            BigDecimal targetPrice,
            long targetQuantity,
            LiquidityProviderQuoteInput input
    ) {
        if (sideOrders.isEmpty()) {
            return SideRetention.NONE;
        }
        List<AutoOrder> cancellations = new ArrayList<>();
        AutoOrder retained = null;
        for (AutoOrder order : sideOrders) {
            if (retained != null || !canRetain(order, side, targetPrice, targetQuantity, input)) {
                cancellations.add(order);
                continue;
            }
            retained = order;
        }
        return new SideRetention(
                retained,
                retained == null ? 0L : retained.remainingQuantity(),
                List.copyOf(cancellations)
        );
    }

    private boolean canRetain(
            AutoOrder order,
            String side,
            BigDecimal targetPrice,
            long targetQuantity,
            LiquidityProviderQuoteInput input
    ) {
        if (targetQuantity <= 0L
                || order.remainingQuantity() <= 0L
                || order.remainingQuantity() > targetQuantity
                || order.limitPrice() == null
                || order.createdAt() == null) {
            return false;
        }
        LiquidityProviderExternalBook externalBook = input.externalBook();
        if (BUY.equals(side)
                && externalBook.bestAsk() != null
                && order.limitPrice().compareTo(externalBook.bestAsk()) >= 0) {
            return false;
        }
        if (SELL.equals(side)
                && externalBook.bestBid() != null
                && order.limitPrice().compareTo(externalBook.bestBid()) <= 0) {
            return false;
        }
        LiquidityProviderMandate mandate = input.mandate();
        long ageSeconds = Math.max(0L, Duration.between(order.createdAt(), input.now()).getSeconds());
        if (ageSeconds >= mandate.orderTtlSeconds()) {
            return false;
        }
        if (ageSeconds < mandate.minimumQuoteLifetimeSeconds()) {
            return true;
        }
        return ticksApart(
                input.marketConfig().market(),
                order.limitPrice(),
                targetPrice,
                mandate.repriceThresholdTicks()
        ) < mandate.repriceThresholdTicks();
    }

    private boolean retainedQuotesCross(
            SideRetention buyRetention,
            SideRetention sellRetention,
            QuotePrices prices
    ) {
        BigDecimal retainedBid = buyRetention.retainedOrder() == null
                ? prices.bidPrice()
                : buyRetention.retainedOrder().limitPrice();
        BigDecimal retainedAsk = sellRetention.retainedOrder() == null
                ? prices.askPrice()
                : sellRetention.retainedOrder().limitPrice();
        return retainedBid.compareTo(retainedAsk) >= 0;
    }

    private int ticksApart(
            String market,
            BigDecimal left,
            BigDecimal right,
            int stopAt
    ) {
        if (left.compareTo(right) == 0) {
            return 0;
        }
        BigDecimal lower = left.min(right);
        BigDecimal upper = left.max(right);
        BigDecimal cursor = AutoMarketPricePolicy.normalizePrice(market, lower);
        int limit = Math.max(1, stopAt);
        for (int ticks = 1; ticks <= limit; ticks++) {
            cursor = AutoMarketPricePolicy.moveByTicks(market, cursor, 1);
            if (cursor.compareTo(upper) >= 0) {
                return ticks;
            }
        }
        return limit;
    }

    private SideAllocations allocateSharedBudget(
            long buyCandidate,
            long sellCandidate,
            long sharedBudget,
            long inventoryDeviationQuantity
    ) {
        if (sharedBudget <= 0L) {
            return SideAllocations.NONE;
        }
        long totalCandidate = saturatingAdd(buyCandidate, sellCandidate);
        if (totalCandidate <= sharedBudget) {
            return new SideAllocations(buyCandidate, sellCandidate);
        }
        if (buyCandidate <= 0L) {
            return new SideAllocations(0L, Math.min(sellCandidate, sharedBudget));
        }
        if (sellCandidate <= 0L) {
            return new SideAllocations(Math.min(buyCandidate, sharedBudget), 0L);
        }
        if (sharedBudget == 1L) {
            return inventoryDeviationQuantity > 0L
                    ? new SideAllocations(0L, 1L)
                    : new SideAllocations(1L, 0L);
        }
        BigDecimal buyShare = BigDecimal.valueOf(sharedBudget)
                .multiply(BigDecimal.valueOf(buyCandidate))
                .divide(BigDecimal.valueOf(totalCandidate), 0, RoundingMode.DOWN);
        long buyAllocation = Math.clamp(buyShare.longValue(), 1L, sharedBudget - 1L);
        buyAllocation = Math.min(buyAllocation, buyCandidate);
        long sellAllocation = Math.min(sellCandidate, sharedBudget - buyAllocation);
        long unused = sharedBudget - buyAllocation - sellAllocation;
        if (unused > 0L) {
            long buyHeadroom = buyCandidate - buyAllocation;
            long buyExtra = Math.min(unused, buyHeadroom);
            buyAllocation += buyExtra;
            unused -= buyExtra;
            sellAllocation += Math.min(unused, sellCandidate - sellAllocation);
        }
        return new SideAllocations(buyAllocation, sellAllocation);
    }

    private void appendProposedOrder(
            List<AutoMarketPlannedOrder> orders,
            LiquidityProviderQuoteInput input,
            String side,
            BigDecimal price,
            long quantity
    ) {
        if (quantity <= 0L) {
            return;
        }
        LocalDateTime expiresAt = input.now().plusSeconds(input.mandate().orderTtlSeconds());
        orders.add(new AutoMarketPlannedOrder(
                input.mandate().accountId(),
                input.mandate().symbol(),
                side,
                price,
                quantity,
                null,
                null,
                expiresAt,
                null,
                null,
                StockOrderOriginType.LIQUIDITY_PROVIDER,
                AutoMarketOrderStrategyOrigin.liquidityProvider(
                        input.mandate().participantId(),
                        input.mandate().id(),
                        input.mandate().policyVersion()
                )
        ));
    }

    private String resolveGateReason(
            LiquidityProviderQuoteInput input,
            QuoteTargets targets,
            long retainedGrossQuantity,
            List<AutoMarketPlannedOrder> proposedOrders,
            long remainingExecutionQuantity,
            long remainingSubmissionQuantity,
            long buyCandidate,
            long sellCandidate
    ) {
        if (!proposedOrders.isEmpty()) {
            return "WITHIN_LIMITS";
        }
        if (retainedGrossQuantity > 0L) {
            return "TARGET_COVERED";
        }
        if (remainingExecutionQuantity <= 0L) {
            return "EXECUTION_LIMIT_REACHED";
        }
        if (remainingSubmissionQuantity <= 0L) {
            return "SUBMISSION_LIMIT_REACHED";
        }
        if (targets.buyQuantity() <= 0L && targets.sellQuantity() <= 0L) {
            if ((input.externalBook().buyDepthQuantity() > 0L && targets.buySingleOrderLimit() <= 0L)
                    || (input.externalBook().sellDepthQuantity() > 0L && targets.sellSingleOrderLimit() <= 0L)) {
                return "EXTERNAL_DEPTH_TOO_THIN";
            }
            return "INVENTORY_BAND_REACHED";
        }
        if (targets.buyQuantity() > 0L && buyCandidate <= 0L
                && targets.sellQuantity() <= 0L) {
            return "INSUFFICIENT_CASH";
        }
        if (targets.sellQuantity() > 0L && sellCandidate <= 0L
                && targets.buyQuantity() <= 0L) {
            return "INSUFFICIENT_INVENTORY";
        }
        return "NO_NEW_QUOTE_CAPACITY";
    }

    private PressureSnapshot pressures(
            LiquidityProviderMandate mandate,
            AutoMarketConfig config
    ) {
        AutoMarketPressure primary = config.primaryPressure();
        AutoMarketPressure secondary = config.secondaryPressure();
        return new PressureSnapshot(
                blend(primary.price(), secondary.price(), mandate.primaryRegimeWeight()),
                blend(primary.volatility(), secondary.volatility(), mandate.primaryRegimeWeight()),
                blend(primary.liquidity(), secondary.liquidity(), mandate.primaryRegimeWeight())
        );
    }

    private BigDecimal currentNetAssetValue(
            LiquidityProviderQuoteInput input,
            List<AutoOrder> allOpenOrders
    ) {
        BigDecimal reservedBuyCash = allOpenOrders.stream()
                .filter(order -> BUY.equals(order.side()))
                .map(AutoOrder::reservedCash)
                .filter(amount -> amount != null && amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentPrice = validMarketPrice(input.marketConfig().currentPrice())
                ? input.marketConfig().currentPrice()
                : BigDecimal.ZERO;
        BigDecimal inventoryValue = currentPrice.multiply(
                BigDecimal.valueOf(Math.max(0L, input.account().holdingQuantity()))
        );
        return input.account().availableCash()
                .add(reservedBuyCash)
                .add(inventoryValue)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private double blend(int primary, int secondary, BigDecimal configuredPrimaryWeight) {
        BigDecimal primaryWeight = configuredPrimaryWeight.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal secondaryWeight = BigDecimal.ONE.subtract(primaryWeight);
        BigDecimal blended = BigDecimal.valueOf(primary)
                .multiply(primaryWeight)
                .add(BigDecimal.valueOf(secondary).multiply(secondaryWeight))
                .movePointLeft(2);
        return Math.clamp(
                blended.doubleValue(),
                -1.0,
                1.0
        );
    }

    private long quantityLimit(long referenceQuantity, BigDecimal rate) {
        if (referenceQuantity <= 0L || rate == null || rate.signum() <= 0) {
            return 0L;
        }
        BigDecimal result = BigDecimal.valueOf(referenceQuantity)
                .multiply(rate)
                .setScale(0, RoundingMode.CEILING);
        return toPositiveLong(result);
    }

    private long multipliedLimit(long baseLimit, BigDecimal multiplier) {
        if (baseLimit <= 0L || multiplier == null || multiplier.signum() <= 0) {
            return 0L;
        }
        return toPositiveLong(
                BigDecimal.valueOf(baseLimit)
                        .multiply(multiplier)
                        .setScale(0, RoundingMode.CEILING)
        );
    }

    private long scaledQuantity(long referenceQuantity, BigDecimal rate, double multiplier) {
        if (referenceQuantity <= 0L || rate == null || rate.signum() <= 0 || multiplier <= 0.0) {
            return 0L;
        }
        return toPositiveLong(
                BigDecimal.valueOf(referenceQuantity)
                        .multiply(rate)
                        .multiply(BigDecimal.valueOf(multiplier))
                        .setScale(0, RoundingMode.CEILING)
        );
    }

    private long scaledQuantity(long baseQuantity, double multiplier) {
        if (baseQuantity <= 0L || multiplier <= 0.0) {
            return 0L;
        }
        return toPositiveLong(
                BigDecimal.valueOf(baseQuantity)
                        .multiply(BigDecimal.valueOf(multiplier))
                        .setScale(0, RoundingMode.HALF_UP)
        );
    }

    private long externalDepthLimit(long depthQuantity, BigDecimal participationRate) {
        if (depthQuantity <= 0L) {
            return Long.MAX_VALUE;
        }
        if (participationRate == null || participationRate.signum() <= 0) {
            return 0L;
        }
        BigDecimal result = BigDecimal.valueOf(depthQuantity)
                .multiply(participationRate)
                .setScale(0, RoundingMode.DOWN);
        return result.signum() <= 0 ? 0L : result.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
    }

    private long applyExternalDepthLimit(long referenceLimit, long externalLimit) {
        return externalLimit == Long.MAX_VALUE ? referenceLimit : Math.min(referenceLimit, externalLimit);
    }

    private long affordableQuantity(BigDecimal cash, BigDecimal price) {
        if (cash == null || cash.signum() <= 0 || price == null || price.signum() <= 0) {
            return 0L;
        }
        return cash.divide(price, 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    private long minPositiveOrZero(long... values) {
        long minimum = Long.MAX_VALUE;
        for (long value : values) {
            if (value <= 0L) {
                return 0L;
            }
            minimum = Math.min(minimum, value);
        }
        return minimum == Long.MAX_VALUE ? 0L : minimum;
    }

    private long toPositiveLong(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return 0L;
        }
        return value.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
    }

    private long proposedQuantity(List<AutoMarketPlannedOrder> orders, String side) {
        return orders.stream()
                .filter(order -> side.equals(order.side()))
                .mapToLong(AutoMarketPlannedOrder::quantity)
                .sum();
    }

    private long signedProjectedInventory(long inventory, long openBuy, long openSell) {
        long withBuys = saturatingAdd(inventory, openBuy);
        return openSell >= withBuys ? 0L : withBuys - openSell;
    }

    private long nonNegativeSubtract(long left, long right) {
        return right >= left ? 0L : left - right;
    }

    private long saturatingAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("Liquidity quantities must not be negative");
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private BigDecimal normalizeWithinLimits(BigDecimal price, AutoMarketConfig config) {
        return AutoMarketPricePolicy.normalizePriceWithinDailyLimit(price, config, config.tickSize());
    }

    private boolean validMarketPrice(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    private List<AutoOrder> sortedOpenOrders(List<AutoOrder> orders) {
        return orders.stream()
                .filter(order -> order != null && order.remainingQuantity() > 0L)
                .sorted(Comparator.comparing(
                                AutoOrder::createdAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparingLong(AutoOrder::id))
                .toList();
    }

    private List<AutoOrder> sideOrders(List<AutoOrder> orders, String side) {
        return orders.stream().filter(order -> side.equals(order.side())).toList();
    }

    private record PressureSnapshot(double price, double volatility, double liquidity) {
    }

    private record NetAssetSnapshot(
            BigDecimal opening,
            BigDecimal current,
            BigDecimal profit
    ) {
    }

    private record QuotePrices(BigDecimal bidPrice, BigDecimal askPrice) {
    }

    private record QuoteTargets(
            long buyQuantity,
            long sellQuantity,
            long buySingleOrderLimit,
            long sellSingleOrderLimit
    ) {
    }

    private record SideRetention(
            AutoOrder retainedOrder,
            long retainedQuantity,
            List<AutoOrder> cancellations
    ) {
        private static final SideRetention NONE = new SideRetention(null, 0L, List.of());
    }

    private record SideAllocations(long buyQuantity, long sellQuantity) {
        private static final SideAllocations NONE = new SideAllocations(0L, 0L);
    }
}
