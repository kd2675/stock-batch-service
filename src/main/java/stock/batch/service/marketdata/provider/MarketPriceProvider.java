package stock.batch.service.marketdata.provider;

import java.math.BigDecimal;

public interface MarketPriceProvider {

    MarketPriceQuote fetch(String symbol, BigDecimal previousPrice);
}
