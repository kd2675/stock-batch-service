package stock.batch.service.batch.automarket.model;

public enum AutoMarketRegimePhase {
    OPENING,
    MIDDAY;

    public static AutoMarketRegimePhase parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return OPENING;
        }
        try {
            return AutoMarketRegimePhase.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return OPENING;
        }
    }
}
