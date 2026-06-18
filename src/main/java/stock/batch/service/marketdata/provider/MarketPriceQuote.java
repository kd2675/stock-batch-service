package stock.batch.service.marketdata.provider;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MarketPriceQuote(
        String symbol,
        BigDecimal currentPrice,
        String provider,
        LocalDateTime priceTime
) {
}
