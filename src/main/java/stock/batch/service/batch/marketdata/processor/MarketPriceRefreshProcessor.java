package stock.batch.service.batch.marketdata.processor;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Component;

import stock.batch.service.batch.marketdata.model.MarketPriceQuoteSnapshot;
import stock.batch.service.batch.marketdata.model.MarketPriceRefreshCommand;
import stock.batch.service.marketdata.provider.MarketPriceQuote;

@Component
public class MarketPriceRefreshProcessor {

    public MarketPriceRefreshCommand process(MarketPriceQuoteSnapshot snapshot) {
        MarketPriceQuote quote = normalizeQuote(snapshot.target().symbol(), snapshot.quote());
        return new MarketPriceRefreshCommand(snapshot.target(), snapshot.referencePrice(), quote);
    }

    private MarketPriceQuote normalizeQuote(String expectedSymbol, MarketPriceQuote quote) {
        if (quote == null) {
            throw new IllegalStateException("Market price provider returned empty quote");
        }
        String normalizedExpectedSymbol = normalizeSymbol(expectedSymbol);
        String normalizedQuoteSymbol = normalizeSymbol(quote.symbol());
        if (!normalizedExpectedSymbol.equals(normalizedQuoteSymbol)) {
            throw new IllegalStateException("Market price provider returned mismatched symbol: expected="
                    + normalizedExpectedSymbol + ", actual=" + normalizedQuoteSymbol);
        }
        if (quote.currentPrice() == null || quote.currentPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Market price provider returned non-positive price: symbol=" + normalizedExpectedSymbol);
        }
        if (quote.priceTime() == null) {
            throw new IllegalStateException("Market price provider returned empty price time: symbol=" + normalizedExpectedSymbol);
        }
        if (quote.provider() == null || quote.provider().isBlank()) {
            throw new IllegalStateException("Market price provider returned empty provider: symbol=" + normalizedExpectedSymbol);
        }
        return new MarketPriceQuote(
                normalizedExpectedSymbol,
                quote.currentPrice(),
                quote.provider().trim(),
                quote.priceTime()
        );
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
