package stock.batch.service.batch.signal.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchJobSignalProcessor {

    private static final String SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN = "AUTO_PARTICIPANT_CASH_FLOW_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_RUN = "MARKET_CLOSE_ROLLOVER_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL = "MARKET_CLOSE_ROLLOVER_SYMBOL";
    private static final String SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL = "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL";

    private final BatchJobSignalReader signalReader;
    private final BatchJobSignalWriter signalWriter;
    private final StockBatchJobLauncher stockBatchJobLauncher;

    public int processPendingSignals(int limit) {
        int processedCount = 0;
        int boundedLimit = Math.max(1, limit);
        for (int index = 0; index < boundedLimit; index++) {
            var signal = signalReader.claimNext();
            if (signal.isEmpty()) {
                break;
            }
            process(signal.get());
            processedCount++;
        }
        return processedCount;
    }

    private void process(BatchJobSignal signal) {
        try {
            StockBatchJobRunResponse response = runSignal(signal);
            signalWriter.complete(signal.id(), response);
        } catch (RuntimeException ex) {
            signalWriter.fail(signal.id(), ex);
            log.warn(
                    "Stock batch signal failed: id={}, type={}, job={}, mode={}, reason={}",
                    signal.id(),
                    signal.signalType(),
                    signal.jobName(),
                    signal.executionMode(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private StockBatchJobRunResponse runSignal(BatchJobSignal signal) {
        return switch (signal.signalType()) {
            case SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN -> stockBatchJobLauncher.fundAutoParticipantsManually();
            case SIGNAL_MARKET_CLOSE_ROLLOVER_RUN -> stockBatchJobLauncher.rolloverClosingPrices();
            case SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL -> stockBatchJobLauncher.rolloverClosingPrices(requireSymbol(signal));
            case SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL -> stockBatchJobLauncher.cancelOpenOrderBookOrders(requireSymbol(signal));
            default -> throw new IllegalArgumentException("Unknown batch job signal type: " + signal.signalType());
        };
    }

    private String requireSymbol(BatchJobSignal signal) {
        if (!StringUtils.hasText(signal.symbol())) {
            throw new IllegalArgumentException("Signal requires symbol: id=" + signal.id());
        }
        return signal.symbol().trim().toUpperCase();
    }
}
