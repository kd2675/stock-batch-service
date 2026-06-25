package stock.batch.service.batch.automarket.model;

import java.util.Arrays;
import java.util.Locale;

public enum AutoParticipantProfileType {
    NEWS_REACTIVE,
    MOMENTUM_FOLLOWER,
    CONTRARIAN,
    LOSS_AVERSE,
    OVERCONFIDENT,
    HERD_FOLLOWER,
    MARKET_MAKER,
    NOISE_TRADER,
    VALUE_ANCHOR,
    SCALPER,
    DAY_TRADER,
    SWING_TRADER,
    LONG_TERM_HOLDER,
    PAYDAY_ACCUMULATOR,
    DIVIDEND_REINVESTOR,
    LIMIT_DOWN_TRAPPED,
    AVERAGE_DOWN_BUYER,
    STOP_LOSS_TRADER,
    FOMO_BUYER,
    PANIC_SELLER,
    DIP_BUYER,
    PROFIT_LOCKER,
    LIQUIDITY_AVOIDANT,
    CASH_DEFENSIVE,
    WHALE,
    SMALL_DIVERSIFIER,
    OBSERVER;

    public static AutoParticipantProfileType defaultType() {
        return NOISE_TRADER;
    }

    public static AutoParticipantProfileType parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return defaultType();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElse(defaultType());
    }
}
