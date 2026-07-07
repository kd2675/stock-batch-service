package stock.batch.service.batch.automarket.model;

import java.util.Locale;

public enum AutoMarketAssetPreference {
    STOCK(1.0),
    CASH(-1.0),
    BALANCED(0.0);

    private final double buyPressureSign;

    AutoMarketAssetPreference(double buyPressureSign) {
        this.buyPressureSign = buyPressureSign;
    }

    public double buyPressureSign() {
        return buyPressureSign;
    }

    public static AutoMarketAssetPreference parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AutoMarketAssetPreference preference : values()) {
            if (preference.name().equals(normalized)) {
                return preference;
            }
        }
        return BALANCED;
    }
}
