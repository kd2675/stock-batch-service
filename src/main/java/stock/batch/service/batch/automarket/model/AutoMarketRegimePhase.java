package stock.batch.service.batch.automarket.model;

import java.time.LocalTime;

public enum AutoMarketRegimePhase {
    SLOT_0600,
    SLOT_0900,
    SLOT_1200,
    SLOT_1500;

    public static AutoMarketRegimePhase from(LocalTime time) {
        if (time == null || time.isBefore(LocalTime.of(9, 0))) {
            return SLOT_0600;
        }
        if (time.isBefore(LocalTime.NOON)) {
            return SLOT_0900;
        }
        if (time.isBefore(LocalTime.of(15, 0))) {
            return SLOT_1200;
        }
        return SLOT_1500;
    }

    public static AutoMarketRegimePhase parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return SLOT_0600;
        }
        try {
            return AutoMarketRegimePhase.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return SLOT_0600;
        }
    }
}
