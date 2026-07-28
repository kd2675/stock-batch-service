package stock.batch.service.batch.automarket.model;

import java.util.Locale;

public enum AutoParticipantBehaviorModelVersion {
    V3;

    public static AutoParticipantBehaviorModelVersion parseRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Auto participant behavior model version is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported auto participant behavior model version: " + value, exception);
        }
    }
}
