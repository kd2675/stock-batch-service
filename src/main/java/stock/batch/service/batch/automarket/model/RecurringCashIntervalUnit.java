package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;
import java.util.Locale;

public enum RecurringCashIntervalUnit {
    SECOND("1"),
    MINUTE("60"),
    HOUR("3600"),
    DAY("86400"),
    MONTH("2592000"),
    YEAR("31536000");

    private final BigDecimal seconds;

    RecurringCashIntervalUnit(String seconds) {
        this.seconds = new BigDecimal(seconds);
    }

    public BigDecimal seconds() {
        return seconds;
    }

    public static RecurringCashIntervalUnit parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return RecurringCashIntervalUnit.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
