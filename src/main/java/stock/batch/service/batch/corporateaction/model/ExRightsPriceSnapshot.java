package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record ExRightsPriceSnapshot(
        BigDecimal closePrice,
        long issuedShares,
        String market
) {
    public ExRightsPriceSnapshot(BigDecimal closePrice, long issuedShares) {
        this(closePrice, issuedShares, "ORDERBOOK");
    }
}
