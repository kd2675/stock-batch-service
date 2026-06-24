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
    PANIC_SELLER,
    DIP_BUYER,
    LIQUIDITY_AVOIDANT,
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
