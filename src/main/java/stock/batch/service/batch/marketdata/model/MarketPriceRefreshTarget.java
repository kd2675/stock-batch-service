package stock.batch.service.batch.marketdata.model;

import java.math.BigDecimal;

public record MarketPriceRefreshTarget(
        String symbol,
        BigDecimal currentPrice,
        BigDecimal referencePrice
) {
}
