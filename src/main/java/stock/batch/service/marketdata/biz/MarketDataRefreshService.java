package stock.batch.service.marketdata.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import stock.batch.service.batch.marketdata.model.MarketPriceRefreshCommand;
import stock.batch.service.batch.marketdata.model.MarketPriceRefreshTarget;
import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.marketdata.processor.MarketPriceRefreshProcessor;
import stock.batch.service.batch.marketdata.reader.MarketPriceQuoteReader;
import stock.batch.service.batch.marketdata.reader.MarketPriceRefreshTargetReader;
import stock.batch.service.batch.marketdata.writer.MarketPriceRefreshWriter;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataRefreshService {

    private final MarketPriceRefreshTargetReader targetReader;
    private final MarketPriceQuoteReader quoteReader;
    private final MarketPriceRefreshProcessor processor;
    private final MarketPriceRefreshWriter writer;
    private final StockPriceRedisPublisher priceRedisPublisher;
    private final MarketDataRefreshTransactionExecutor transactionExecutor;
    private final MarketSessionFenceService marketSessionFenceService;

    public int refreshWatchedPrices() {
        return refreshWatchedPrices(false);
    }

    public int refreshWatchedPricesStrict() {
        return refreshWatchedPrices(true);
    }

    private int refreshWatchedPrices(boolean failOnAnyTarget) {
        if (failOnAnyTarget) {
            marketSessionFenceService.assertMarketLedgerMutationAllowed("pre-open market data refresh");
        }
        int refreshedCount = 0;
        int failedCount = 0;
        String firstFailedSymbol = null;
        RuntimeException firstFailure = null;
        for (MarketPriceRefreshTarget target : targetReader.readTargets()) {
            try {
                MarketPriceRefreshCommand command = processor.process(quoteReader.readQuote(target));
                transactionExecutor.execute(() -> writer.write(command), failOnAnyTarget);
                if (failOnAnyTarget) {
                    priceRedisPublisher.publishStrict(
                            command.quote().symbol(),
                            command.quote().currentPrice(),
                            command.quote().priceTime(),
                            command.quote().provider()
                    );
                } else {
                    priceRedisPublisher.publish(
                            command.quote().symbol(),
                            command.quote().currentPrice(),
                            command.quote().priceTime(),
                            command.quote().provider()
                    );
                }
                refreshedCount++;
            } catch (RuntimeException ex) {
                failedCount++;
                if (firstFailure == null) {
                    firstFailedSymbol = target.symbol();
                    firstFailure = ex;
                }
                log.warn("Market price refresh skipped: symbol={}, reason={}", target.symbol(), ex.getMessage());
            }
        }
        if (failOnAnyTarget && failedCount > 0) {
            throw new IllegalStateException(
                    "Pre-open market price refresh failed: failedCount=%d, firstFailedSymbol=%s"
                            .formatted(failedCount, firstFailedSymbol),
                    firstFailure
            );
        }
        return refreshedCount;
    }
}
