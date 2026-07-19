package stock.batch.service.batch.metadata.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.LightweightBatchTask;
import stock.batch.service.batch.metadata.biz.BatchMetadataRetentionService;

@Component
@RequiredArgsConstructor
public class BatchMetadataRetentionJob implements LightweightBatchTask {

    public static final String JOB_NAME = "batch-metadata-retention";
    private static final String EXECUTION_MODE = "post-close-metadata-retention";

    private final BatchMetadataRetentionService retentionService;

    @Override
    public String taskName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public boolean requiresJobLock() {
        return true;
    }

    @Override
    public int run() {
        throw new IllegalStateException("Batch metadata retention requires a post-close cycle");
    }

    @Override
    public int runForPostCloseCycle(long closeCycleId) {
        BatchMetadataRetentionService.RetentionResult result =
                retentionService.archiveAndOptionallyPurgeCompletedInstances();
        if (result.failedInstances() > 0) {
            throw new IllegalStateException(
                    "Batch metadata retention failed for " + result.failedInstances() + " job instance(s)"
            );
        }
        return result.processedInstances();
    }
}
