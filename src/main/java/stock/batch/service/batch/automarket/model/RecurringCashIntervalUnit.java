package stock.batch.service.batch.automarket.model;

import java.util.Locale;

public enum RecurringCashIntervalUnit {
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    MONTH,
    YEAR;

    public static RecurringCashIntervalUnit parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return RecurringCashIntervalUnit.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
