package stock.batch.service.batch.execution.job;

import org.junit.jupiter.api.Test;

import stock.batch.service.execution.biz.ExecutionAccountDaySummaryAccumulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionAccountDaySummaryFlushJobTest {

    @Test
    void requiresJobLock_multiInstanceFlushIsSerialized() {
        ExecutionAccountDaySummaryFlushJob job = new ExecutionAccountDaySummaryFlushJob(
                mock(ExecutionAccountDaySummaryAccumulator.class)
        );

        assertThat(job.requiresJobLock()).isTrue();
    }

    @Test
    void hasWork_delegatesToBoundedInMemoryAccumulator() {
        ExecutionAccountDaySummaryAccumulator accumulator = mock(ExecutionAccountDaySummaryAccumulator.class);
        when(accumulator.hasPendingDeltas()).thenReturn(true);
        ExecutionAccountDaySummaryFlushJob job = new ExecutionAccountDaySummaryFlushJob(accumulator);

        assertThat(job.hasWork()).isTrue();
    }
}
