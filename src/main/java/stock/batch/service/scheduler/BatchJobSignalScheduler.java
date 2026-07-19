package stock.batch.service.scheduler;

import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    void validateVolumeConfiguration() {
        BatchJobSignalProcessor.validateChunkLimit(chunkLimit);
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.signal.initial-delay-ms:3000}",
            fixedDelayString = "${stock.batch.signal.fixed-delay-ms:5000}"
    )
    public void processBatchJobSignals() {
        batchJobSignalProcessor.processPendingSignals(chunkLimit);
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.signal.dead-letter-initial-delay-ms:30000}",
            fixedDelayString = "${stock.batch.signal.dead-letter-fixed-delay-ms:60000}"
    )
    public void deadLetterExhaustedSignals() {
        batchJobSignalProcessor.deadLetterExhaustedSignals();
    }
}
