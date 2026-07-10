package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record ExRightsPriceSnapshot(
        BigDecimal closePrice,
        long issuedShares
) {
}
