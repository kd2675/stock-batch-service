package stock.batch.service.automarket.profile;

public enum ProfileInventoryMode {
    SIGNAL_DRIVEN,
    TARGET_ALLOCATION;

    static ProfileInventoryMode parseOrDefault(String value, ProfileInventoryMode defaultValue) {
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
