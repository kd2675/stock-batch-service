package stock.batch.service.automarket.profile;

public enum ProfilePricingMode {
    DIRECTIONAL,
    MARKET_MAKING;

    static ProfilePricingMode parseOrDefault(String value, ProfilePricingMode defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return defaultValue;
        }
    }
}
