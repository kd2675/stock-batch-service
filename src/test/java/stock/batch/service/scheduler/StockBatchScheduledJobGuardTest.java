package stock.batch.service.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockBatchScheduledJobGuardTest {

    @Test
    void runOptionalBatchIfEnabled_runtimeDisabled_completesWithoutRunningMaintenance() {
        BatchJobRuntimeControl runtimeControl = mock(BatchJobRuntimeControl.class);
        when(runtimeControl.shouldRunScheduledJob("holding-cleanup", true)).thenReturn(false);
        StockBatchScheduledJobGuard guard = new StockBatchScheduledJobGuard(runtimeControl);
        AtomicBoolean executed = new AtomicBoolean(false);

        StockBatchJobRunResponse response = guard.runOptionalBatchIfEnabled(
                "holding-cleanup",
                true,
                () -> {
                    executed.set(true);
                    return null;
                }
        );

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isZero();
        assertThat(executed).isFalse();
    }

    @Test
    void runOptionalBatchIfEnabled_shutdown_doesNotAdvancePhase() {
        BatchJobRuntimeControl runtimeControl = mock(BatchJobRuntimeControl.class);
        StockBatchScheduledJobGuard guard = new StockBatchScheduledJobGuard(runtimeControl);
        guard.prepareShutdown();

        StockBatchJobRunResponse response = guard.runOptionalBatchIfEnabled(
                "holding-cleanup",
                true,
                () -> null
        );

        assertThat(response.status()).isEqualTo("SKIPPED");
        verifyNoInteractions(runtimeControl);
    }
}
