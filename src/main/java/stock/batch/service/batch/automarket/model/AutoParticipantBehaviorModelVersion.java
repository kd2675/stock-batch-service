package stock.batch.service.batch.automarket.model;

import java.util.Locale;

public enum AutoParticipantBehaviorModelVersion {
    V1,
    V2;

    public static AutoParticipantBehaviorModelVersion parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return V1;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return V1;
        }
    }
}
