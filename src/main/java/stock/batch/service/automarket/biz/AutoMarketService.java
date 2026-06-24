package stock.batch.service.automarket.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;

@Service
@RequiredArgsConstructor
public class AutoMarketService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.valueOf(100);

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketWriter autoMarketWriter;

    @Transactional
    public int runAutoMarketStep() {
        List<AutoParticipant> participants = autoMarketReader.findEnabledParticipants();
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (participants.isEmpty() || configs.isEmpty()) {
            return 0;
        }

        int processed = 0;
        ensureAccounts(participants);
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = autoMarketReader.findEnabledParticipantStrategies(config);
            if (strategies.isEmpty()) {
                continue;
            }
            processed += expireOldAutoOrders(config);
            processed += placeAutoOrders(strategies, config);
        }
        return processed;
    }

    private void ensureAccounts(List<AutoParticipant> participants) {
        LocalDateTime now = LocalDateTime.now();
        for (AutoParticipant participant : participants) {
            if (autoMarketReader.accountExists(participant.userKey())) {
                continue;
            }
            autoMarketWriter.insertAccount(participant, now);
        }
    }

    private int expireOldAutoOrders(AutoMarketConfig config) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(config.orderTtlSeconds());
        List<AutoOrder> orders = autoMarketReader.findExpiredAutoOrders(config, threshold);

        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AutoOrder order : orders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                autoMarketWriter.creditCash(order.accountId(), order.reservedCash(), now);
            }
            if (SELL.equals(order.side())) {
                autoMarketWriter.releaseReservedSellQuantity(order, now);
            }
            autoMarketWriter.cancelOrder(order, now);
            expired++;
        }
        return expired;
    }

    private int placeAutoOrders(List<AutoParticipantStrategy> strategies, AutoMarketConfig config) {
        int placed = 0;
        for (AutoParticipantStrategy strategy : strategies) {
            int effectiveIntensity = effectiveIntensity(strategy, config);
            int movementStrength = Math.max(effectiveIntensity, 11 - effectiveIntensity);
            int orderCount = Math.max(1, (int) Math.ceil(movementStrength / 3.5));
            for (int index = 0; index < orderCount; index++) {
                String side = chooseSide(strategy, config, effectiveIntensity);
                long quantity = randomInt(1, config.maxOrderQuantity());
                BigDecimal price = createAutoPrice(config, effectiveIntensity, side);
                if (placeOrder(strategy.accountId(), config.symbol(), side, price, quantity)) {
                    placed++;
                }
            }
        }
        return placed;
    }

    private String chooseSide(AutoParticipantStrategy strategy, AutoMarketConfig config, int effectiveIntensity) {
        long availableQuantity = autoMarketReader.getAvailableQuantity(strategy.accountId(), config.symbol());
        if (availableQuantity <= 0) {
            return BUY;
        }
        BigDecimal cashBalance = autoMarketReader.getCashBalance(strategy.accountId());
        BigDecimal oneOrderBudget = config.currentPrice().multiply(BigDecimal.valueOf(config.maxOrderQuantity()));
        if (cashBalance.compareTo(oneOrderBudget) < 0) {
            return SELL;
        }
        if (effectiveIntensity >= 9) {
            return BUY;
        }
        if (effectiveIntensity <= 2) {
            return SELL;
        }
        double pressure = pricePressure(effectiveIntensity);
        double inventoryPenalty = Math.min(availableQuantity, 20) * 0.01;
        double buyBias = clampDouble(0.12, 0.88, 0.5 + pressure * 0.38 - inventoryPenalty);
        return ThreadLocalRandom.current().nextDouble() < buyBias ? BUY : SELL;
    }

    int effectiveIntensity(AutoParticipantStrategy strategy, AutoMarketConfig config) {
        Integer reportScore = config.reportScore();
        if (reportScore == null) {
            return clamp(strategy.intensity(), 1, 10);
        }
        double blended = strategy.intensity() * 0.65 + clamp(reportScore, 1, 10) * 0.35;
        return clamp((int) Math.round(blended), 1, 10);
    }

    private BigDecimal createAutoPrice(AutoMarketConfig config, int intensity, String side) {
        BigDecimal bestBid = autoMarketReader.findBestPrice(config.symbol(), BUY);
        BigDecimal bestAsk = autoMarketReader.findBestPrice(config.symbol(), SELL);
        BigDecimal tick = config.tickSize();
        double pressure = pricePressure(intensity);
        double pressureStrength = Math.abs(pressure);
        boolean upwardAggressive = pressure > 0 && ThreadLocalRandom.current().nextDouble() < 0.35 + pressureStrength * 0.45;
        boolean downwardAggressive = pressure < 0 && ThreadLocalRandom.current().nextDouble() < 0.35 + pressureStrength * 0.45;

        if (BUY.equals(side) && upwardAggressive && bestAsk != null) {
            return normalizePrice(bestAsk.add(tick.multiply(BigDecimal.valueOf(randomInt(0, 1)))), tick);
        }
        if (SELL.equals(side) && downwardAggressive && bestBid != null) {
            return normalizePrice(bestBid.subtract(tick.multiply(BigDecimal.valueOf(randomInt(0, 1)))).max(tick), tick);
        }

        int maxSpreadTicks = 2 + (int) Math.ceil(pressureStrength * 6);
        BigDecimal spread = tick.multiply(BigDecimal.valueOf(randomInt(1, maxSpreadTicks)));
        BigDecimal directionalOffset = tick.multiply(BigDecimal.valueOf(Math.round(pressure * 2)));
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            rawPrice = config.currentPrice().add(directionalOffset).subtract(pressure < 0 ? spread : BigDecimal.ZERO);
        } else {
            rawPrice = config.currentPrice().add(directionalOffset).add(pressure > 0 ? spread : BigDecimal.ZERO);
        }
        return normalizePrice(rawPrice.max(tick), tick);
    }

    private boolean placeOrder(long accountId, String symbol, String side, BigDecimal price, long quantity) {
        LocalDateTime now = LocalDateTime.now();
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

        autoMarketWriter.insertLimitOrder(
                nextClientOrderId(),
                accountId,
                symbol,
                side,
                price,
                quantity,
                reservedCash,
                now
        );
        return true;
    }

    private BigDecimal normalizePrice(BigDecimal rawPrice, BigDecimal tick) {
        BigDecimal normalizedTick = positiveOrDefault(tick, DEFAULT_TICK_SIZE);
        BigDecimal ticks = rawPrice.divide(normalizedTick, 0, RoundingMode.HALF_UP);
        return ticks.multiply(normalizedTick).max(normalizedTick).setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? defaultValue : value;
    }

    private double pricePressure(int intensity) {
        return (clamp(intensity, 1, 10) - 5.5) / 4.5;
    }

    private double clampDouble(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }

    private String nextClientOrderId() {
        return "auto-" + UUID.randomUUID().toString().replace("-", "");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}
