package stock.batch.service.batch.marketdata.reader;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.marketdata.model.MarketPriceQuoteSnapshot;
import stock.batch.service.batch.marketdata.model.MarketPriceRefreshTarget;
import stock.batch.service.marketdata.provider.MarketPriceProvider;

@Component
@RequiredArgsConstructor
public class MarketPriceQuoteReader {

    private static final BigDecimal DEFAULT_REFERENCE_PRICE = BigDecimal.ONE;

    private final MarketPriceProvider marketPriceProvider;

    public MarketPriceQuoteSnapshot readQuote(MarketPriceRefreshTarget target) {
        BigDecimal referencePrice = resolveReferencePrice(target);
        return new MarketPriceQuoteSnapshot(
                target,
                referencePrice,
                marketPriceProvider.fetch(target.symbol(), referencePrice)
        );
    }

    private BigDecimal resolveReferencePrice(MarketPriceRefreshTarget target) {
        if (target.currentPrice() != null) {
            return target.currentPrice();
        }
        if (target.referencePrice() != null && target.referencePrice().compareTo(BigDecimal.ZERO) > 0) {
            return target.referencePrice();
        }
        return DEFAULT_REFERENCE_PRICE;
    }
}
