package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.util.Map;

record InstitutionAccountSnapshot(
        BigDecimal availableCash,
        BigDecimal openBuyReservedCash,
        BigDecimal totalHoldingValue,
        Map<String, InstitutionPositionSnapshot> positions
) {

    InstitutionAccountSnapshot {
        availableCash = nonNegative(availableCash);
        openBuyReservedCash = nonNegative(openBuyReservedCash);
        totalHoldingValue = nonNegative(totalHoldingValue);
        positions = Map.copyOf(positions);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
