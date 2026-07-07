package stock.batch.service.batch.automarket.model;

import java.util.Locale;

public enum AutoMarketPriceDirection {
    UP(1.0),
    DOWN(-1.0),
    NEUTRAL(0.0);

    private final double pressureSign;

    AutoMarketPriceDirection(double pressureSign) {
        this.pressureSign = pressureSign;
    }

    public double pressureSign() {
        return pressureSign;
    }

    public static AutoMarketPriceDirection parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return NEUTRAL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AutoMarketPriceDirection direction : values()) {
            if (direction.name().equals(normalized)) {
                return direction;
            }
        }
        return NEUTRAL;
    }
}
