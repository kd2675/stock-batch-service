package stock.batch.service.batch.metadata.job;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.metadata.biz.BatchMetadataRetentionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchMetadataRetentionJobTest {

    @Test
    void runForPostCloseCycle_successfulBoundedRun_returnsProcessedInstanceCount() {
        BatchMetadataRetentionService service = mock(BatchMetadataRetentionService.class);
        when(service.archiveAndOptionallyPurgeCompletedInstances()).thenReturn(result(3, 0));

        int processed = new BatchMetadataRetentionJob(service).runForPostCloseCycle(10L);

        assertThat(processed).isEqualTo(3);
    }

    @Test
    void runForPostCloseCycle_partialFailure_reportsFailedLightweightRun() {
        BatchMetadataRetentionService service = mock(BatchMetadataRetentionService.class);
        when(service.archiveAndOptionallyPurgeCompletedInstances()).thenReturn(result(2, 1));

        assertThatThrownBy(() -> new BatchMetadataRetentionJob(service).runForPostCloseCycle(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 job instance");
    }

    private BatchMetadataRetentionService.RetentionResult result(int processed, int failures) {
        return new BatchMetadataRetentionService.RetentionResult(
                processed + failures,
                processed,
                processed,
                processed,
                0,
                0,
                failures,
                LocalDateTime.of(2026, 6, 16, 0, 0)
        );
    }
}
