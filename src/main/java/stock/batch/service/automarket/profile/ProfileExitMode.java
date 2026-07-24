package stock.batch.service.automarket.profile;

public enum ProfileExitMode {
    SIGNAL_DRIVEN,
    TAKE_PROFIT_FIRST,
    HOLD_LOSSES;

    static ProfileExitMode parseOrDefault(String value, ProfileExitMode defaultValue) {
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
