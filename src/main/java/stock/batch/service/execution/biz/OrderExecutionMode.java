package stock.batch.service.execution.biz;

import java.util.Arrays;
import java.util.Locale;

public enum OrderExecutionMode {
    VIRTUAL_MARKET_PRICE("virtual-market-price"),
    INTERNAL_ORDER_BOOK("internal-order-book");

    private final String propertyValue;

    OrderExecutionMode(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public String propertyValue() {
        return propertyValue;
    }

    public static OrderExecutionMode fromProperty(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.propertyValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported stock batch execution mode: " + value));
    }
}
