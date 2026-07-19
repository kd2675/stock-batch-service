package stock.batch.service.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.batch.common.support.StockBatchJobRunner;
import stock.batch.service.batch.execution.job.ExecutionAccountDaySummaryFlushJob;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionAccountDaySummarySchedulerTest {

    @Test
    void validateVolumeConfiguration_flushIntervalBelowBound_throws() {
        ExecutionAccountDaySummaryScheduler scheduler = new ExecutionAccountDaySummaryScheduler(
                mock(ExecutionAccountDaySummaryFlushJob.class),
                mock(StockBatchJobRunner.class),
                mock(StockBatchScheduledJobGuard.class),
                mock(MarketSessionFenceService.class)
        );
        ReflectionTestUtils.setField(scheduler, "flushFixedDelayMs", 29_999L);

        assertThatThrownBy(scheduler::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be at least 30000");
    }

    @Test
    void flush_noPendingDelta_skipsRuntimeControlAndDatabaseJobLock() {
        ExecutionAccountDaySummaryFlushJob flushJob = mock(ExecutionAccountDaySummaryFlushJob.class);
        StockBatchJobRunner runner = mock(StockBatchJobRunner.class);
        StockBatchScheduledJobGuard guard = mock(StockBatchScheduledJobGuard.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(flushJob.hasWork()).thenReturn(false);
        ExecutionAccountDaySummaryScheduler scheduler = new ExecutionAccountDaySummaryScheduler(
                flushJob,
                runner,
                guard,
                marketSessionFenceService
        );

        scheduler.flush();

        verifyNoInteractions(runner, guard);
    }

    @Test
    void flush_marketClosed_defersDerivedSummaryToOvernightRebuild() {
        ExecutionAccountDaySummaryFlushJob flushJob = mock(ExecutionAccountDaySummaryFlushJob.class);
        StockBatchJobRunner runner = mock(StockBatchJobRunner.class);
        StockBatchScheduledJobGuard guard = mock(StockBatchScheduledJobGuard.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(flushJob.hasWork()).thenReturn(true);
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        ExecutionAccountDaySummaryScheduler scheduler = new ExecutionAccountDaySummaryScheduler(
                flushJob,
                runner,
                guard,
                marketSessionFenceService
        );

        scheduler.flush();

        verifyNoInteractions(runner, guard);
    }
}
