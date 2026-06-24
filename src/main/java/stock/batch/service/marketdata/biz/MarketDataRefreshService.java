package stock.batch.service.marketdata.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.marketdata.model.MarketPriceRefreshCommand;
import stock.batch.service.batch.marketdata.model.MarketPriceRefreshTarget;
import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.marketdata.processor.MarketPriceRefreshProcessor;
import stock.batch.service.batch.marketdata.reader.MarketPriceQuoteReader;
import stock.batch.service.batch.marketdata.reader.MarketPriceRefreshTargetReader;
import stock.batch.service.batch.marketdata.writer.MarketPriceRefreshWriter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataRefreshService {

    private final MarketPriceRefreshTargetReader targetReader;
    private final MarketPriceQuoteReader quoteReader;
    private final MarketPriceRefreshProcessor processor;
    private final MarketPriceRefreshWriter writer;
    private final StockPriceRedisPublisher priceRedisPublisher;

    @Transactional
    public int refreshWatchedPrices() {
        int refreshedCount = 0;
        for (MarketPriceRefreshTarget target : targetReader.readTargets()) {
            try {
                MarketPriceRefreshCommand command = processor.process(quoteReader.readQuote(target));
                writer.write(command);
                priceRedisPublisher.publish(
                        command.quote().symbol(),
                        command.quote().currentPrice(),
                        command.quote().priceTime(),
                        command.quote().provider()
                );
                refreshedCount++;
            } catch (RuntimeException ex) {
                log.warn("Market price refresh skipped: symbol={}, reason={}", target.symbol(), ex.getMessage());
            }
        }
        return refreshedCount;
    }
}
