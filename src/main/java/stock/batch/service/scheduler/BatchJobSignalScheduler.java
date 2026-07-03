package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.signal.biz.BatchJobSignalProcessor;

@Component
@RequiredArgsConstructor
public class BatchJobSignalScheduler {

    private final BatchJobSignalProcessor batchJobSignalProcessor;

    @Value("${stock.batch.signal.chunk-limit:20}")
    private int chunkLimit;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.signal.initial-delay-ms:3000}",
            fixedDelayString = "${stock.batch.signal.fixed-delay-ms:1000}"
    )
    public void processBatchJobSignals() {
        batchJobSignalProcessor.processPendingSignals(chunkLimit);
    }
}
