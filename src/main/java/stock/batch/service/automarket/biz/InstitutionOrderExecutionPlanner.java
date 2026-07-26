package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.SplittableRandom;

import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;

@Component
class InstitutionOrderExecutionPlanner {

    private static final BigDecimal MAX_SINGLE_ORDER_REFERENCE_RATE =
            new BigDecimal("0.005000");
    private static final BigDecimal MAX_AGGRESSIVE_DEPTH_RATE =
            new BigDecimal("0.100000");
    private static final double MIN_AGGRESSIVE_PROBABILITY = 0.05;
    private static final double MAX_AGGRESSIVE_PROBABILITY = 0.15;
    private static final int ORDER_TTL_SECONDS = 600;

    InstitutionOrderExecutionPlan plan(
            InstitutionOrderIntent intent,
            AutoMarketConfig config,
            InstitutionExternalBook externalBook,
            LocalDateTime now
    ) {
        String validationFailure = intent.validationFailure();
        if (validationFailure != null) {
            return InstitutionOrderExecutionPlan.rejected(validationFailure);
        }
        if (config == null
                || !intent.symbol().equals(config.symbol())
                || config.currentPrice() == null
                || config.currentPrice().signum() <= 0
                || config.previousClose() == null
                || config.previousClose().signum() <= 0) {
            return InstitutionOrderExecutionPlan.rejected("MARKET_CONFIG_INVALID");
        }
        InstitutionExternalBook book = externalBook == null
                ? InstitutionExternalBook.EMPTY
                : externalBook;
        long singleOrderLimit = Math.max(
                1L,
                floorToLong(
                        BigDecimal.valueOf(intent.referenceDailyVolume())
                                .multiply(MAX_SINGLE_ORDER_REFERENCE_RATE)
                )
        );
        long quantity = Math.min(intent.requestedQuantity(), singleOrderLimit);
        if (quantity <= 0L) {
            return InstitutionOrderExecutionPlan.rejected("SINGLE_ORDER_LIMIT_EMPTY");
        }

        boolean aggressive = shouldAggress(intent, book);
        if (aggressive) {
            long oppositeDepth = "BUY".equals(intent.side())
                    ? book.sellDepthQuantity()
                    : book.buyDepthQuantity();
            long depthLimit = floorToLong(
                    BigDecimal.valueOf(oppositeDepth).multiply(MAX_AGGRESSIVE_DEPTH_RATE)
            );
            if (depthLimit <= 0L) {
                aggressive = false;
            } else {
                quantity = Math.min(quantity, depthLimit);
            }
        }
        BigDecimal price = aggressive
                ? aggressivePrice(intent.side(), book)
                : passivePrice(intent.side(), config, book);
        if (price == null || price.signum() <= 0 || quantity <= 0L) {
            return InstitutionOrderExecutionPlan.rejected("EXECUTION_PRICE_OR_DEPTH_UNAVAILABLE");
        }
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(
                config.market(),
                price
        );
        BigDecimal normalizedPrice = AutoMarketPricePolicy.normalizePriceWithinDailyLimit(
                price,
                config,
                tick
        );
        if (aggressive && !crossesOpposite(intent.side(), normalizedPrice, book)) {
            return InstitutionOrderExecutionPlan.rejected(
                    "AGGRESSIVE_PRICE_OUTSIDE_DAILY_LIMIT"
            );
        }
        long plannedNotionalQuantity = floorToLong(
                intent.plannedAmount().divide(normalizedPrice, 0, RoundingMode.DOWN)
        );
        quantity = Math.min(quantity, plannedNotionalQuantity);
        if (quantity <= 0L) {
            return InstitutionOrderExecutionPlan.rejected("PLANNED_NOTIONAL_LIMIT_EMPTY");
        }
        if (!aggressive && crossesOpposite(intent.side(), normalizedPrice, book)) {
            return InstitutionOrderExecutionPlan.rejected("PASSIVE_PRICE_WOULD_CROSS");
        }
        return new InstitutionOrderExecutionPlan(
                true,
                aggressive ? "AGGRESSIVE_WITHIN_15_PERCENT_CAP" : "PASSIVE_LIMIT",
                normalizedPrice,
                quantity,
                aggressive,
                now.plusSeconds(ORDER_TTL_SECONDS)
        );
    }

    private boolean shouldAggress(
            InstitutionOrderIntent intent,
            InstitutionExternalBook book
    ) {
        boolean hasOppositeQuote = "BUY".equals(intent.side())
                ? book.bestAsk() != null && book.sellDepthQuantity() > 0L
                : book.bestBid() != null && book.buyDepthQuantity() > 0L;
        if (!hasOppositeQuote) {
            return false;
        }
        double pressure = intent.executionAggressionPressure() == null
                ? 0.0
                : Math.clamp(
                        intent.executionAggressionPressure().doubleValue(),
                        -1.0,
                        1.0
                );
        double normalized = (pressure + 1.0) / 2.0;
        double probability = MIN_AGGRESSIVE_PROBABILITY
                + normalized * (MAX_AGGRESSIVE_PROBABILITY - MIN_AGGRESSIVE_PROBABILITY);
        long seed = intent.deterministicSeed()
                ^ ((long) intent.symbol().hashCode() << 32)
                ^ intent.side().hashCode();
        return new SplittableRandom(seed).nextDouble() < probability;
    }

    private BigDecimal aggressivePrice(String side, InstitutionExternalBook book) {
        return "BUY".equals(side) ? book.bestAsk() : book.bestBid();
    }

    private BigDecimal passivePrice(
            String side,
            AutoMarketConfig config,
            InstitutionExternalBook book
    ) {
        if ("BUY".equals(side)) {
            BigDecimal candidate = book.bestBid() == null
                    ? AutoMarketPricePolicy.moveByTicks(
                            config.market(),
                            config.currentPrice(),
                            -1
                    )
                    : book.bestBid();
            if (book.bestAsk() != null && candidate.compareTo(book.bestAsk()) >= 0) {
                candidate = AutoMarketPricePolicy.moveByTicks(
                        config.market(),
                        book.bestAsk(),
                        -1
                );
            }
            return candidate;
        }
        BigDecimal candidate = book.bestAsk() == null
                ? AutoMarketPricePolicy.moveByTicks(
                        config.market(),
                        config.currentPrice(),
                        1
                )
                : book.bestAsk();
        if (book.bestBid() != null && candidate.compareTo(book.bestBid()) <= 0) {
            candidate = AutoMarketPricePolicy.moveByTicks(
                    config.market(),
                    book.bestBid(),
                    1
            );
        }
        return candidate;
    }

    private boolean crossesOpposite(
            String side,
            BigDecimal price,
            InstitutionExternalBook book
    ) {
        if ("BUY".equals(side)) {
            return book.bestAsk() != null && price.compareTo(book.bestAsk()) >= 0;
        }
        return book.bestBid() != null && price.compareTo(book.bestBid()) <= 0;
    }

    private long floorToLong(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return 0L;
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) {
            return Long.MAX_VALUE;
        }
        return value.setScale(0, RoundingMode.DOWN).longValueExact();
    }
}
