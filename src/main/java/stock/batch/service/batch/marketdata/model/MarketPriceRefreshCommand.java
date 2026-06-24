package stock.batch.service.batch.marketdata.model;

import java.math.BigDecimal;

import stock.batch.service.marketdata.provider.MarketPriceQuote;

public record MarketPriceRefreshCommand(
        MarketPriceRefreshTarget target,
        BigDecimal referencePrice,
        MarketPriceQuote quote
) {
}
