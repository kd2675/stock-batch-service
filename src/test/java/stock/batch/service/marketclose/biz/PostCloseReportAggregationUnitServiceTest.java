package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.CannotAcquireLockException;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;
import stock.batch.service.batch.execution.job.ExecutionAccountDaySummaryFlushJob;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.execution.biz.ExecutionAccountDaySummaryAccumulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostCloseReportAggregationUnitServiceTest {

    private static final String SUMMARY_LOCK_OWNER = "summary-rebuild-owner";

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDateTime REBUILT_AT = BUSINESS_DATE.plusDays(1).atStartOfDay();

    @Test
    void aggregateSymbolReport_restartReplacement_deletesOnlyOneSymbolBeforeInsert() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        ExecutionAccountDaySummaryAccumulator accumulator = mock(ExecutionAccountDaySummaryAccumulator.class);
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        PostCloseReportAggregationUnitService service = new PostCloseReportAggregationUnitService(
                writer,
                accumulator,
                lockRegistry,
                mock(AutoParticipantFundingBudgetService.class),
                new ResourcelessTransactionManager()
        );
        when(writer.snapshotOrderBookDailySymbols(
                7L,
                10L,
                "DEMO001",
                BUSINESS_DATE,
                REBUILT_AT,
                BUSINESS_DATE.atStartOfDay(),
                BUSINESS_DATE.plusDays(1).atStartOfDay()
        )).thenReturn(1);

        int inserted = service.aggregateSymbolReport(
                7L,
                10L,
                "DEMO001",
                BUSINESS_DATE,
                REBUILT_AT
        );

        assertThat(inserted).isEqualTo(1);
        var ordered = inOrder(writer);
        ordered.verify(writer).deleteOrderBookDailySnapshot(10L, "DEMO001");
        ordered.verify(writer).snapshotOrderBookDailySymbols(
                7L,
                10L,
                "DEMO001",
                BUSINESS_DATE,
                REBUILT_AT,
                BUSINESS_DATE.atStartOfDay(),
                BUSINESS_DATE.plusDays(1).atStartOfDay()
        );
        ordered.verify(writer).updateClosePriceLastExecutionId(
                7L,
                "DEMO001",
                BUSINESS_DATE.atStartOfDay(),
                BUSINESS_DATE.plusDays(1).atStartOfDay()
        );
    }

    @Test
    void aggregateAccountReport_restartReplacement_deletesOnlyOneSymbolBeforeInsert() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        ExecutionAccountDaySummaryAccumulator accumulator = mock(ExecutionAccountDaySummaryAccumulator.class);
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        PostCloseReportAggregationUnitService service = new PostCloseReportAggregationUnitService(
                writer,
                accumulator,
                lockRegistry,
                mock(AutoParticipantFundingBudgetService.class),
                new ResourcelessTransactionManager()
        );
        when(writer.snapshotDailyAccountExecutions(
                7L,
                10L,
                "DEMO001",
                BUSINESS_DATE,
                REBUILT_AT,
                BUSINESS_DATE.atStartOfDay(),
                BUSINESS_DATE.plusDays(1).atStartOfDay()
        )).thenReturn(4);

        int inserted = service.aggregateAccountReport(7L, 10L, "DEMO001", BUSINESS_DATE, REBUILT_AT);

        assertThat(inserted).isEqualTo(4);
        var ordered = inOrder(writer);
        ordered.verify(writer).deleteDailyAccountExecutionSnapshot(10L, "DEMO001");
        ordered.verify(writer).snapshotDailyAccountExecutions(
                7L,
                10L,
                "DEMO001",
                BUSINESS_DATE,
                REBUILT_AT,
                BUSINESS_DATE.atStartOfDay(),
                BUSINESS_DATE.plusDays(1).atStartOfDay()
        );
    }

    @Test
    void rebuildAccountDaySummary_flushLockBusy_defersWithoutRebuilding() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        ExecutionAccountDaySummaryAccumulator accumulator = mock(ExecutionAccountDaySummaryAccumulator.class);
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        PostCloseReportAggregationUnitService service = new PostCloseReportAggregationUnitService(
                writer,
                accumulator,
                lockRegistry,
                mock(AutoParticipantFundingBudgetService.class),
                new ResourcelessTransactionManager()
        );

        assertThatThrownBy(() -> service.rebuildAccountDaySummary(BUSINESS_DATE, REBUILT_AT))
                .isInstanceOf(CannotAcquireLockException.class);
        verify(accumulator, never()).rebuildDate(eq(BUSINESS_DATE), any());
    }

    @Test
    void rebuildAccountDaySummary_flushLockAcquired_rebuildsAndReleasesLock() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        ExecutionAccountDaySummaryAccumulator accumulator = mock(ExecutionAccountDaySummaryAccumulator.class);
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        PostCloseReportAggregationUnitService service = new PostCloseReportAggregationUnitService(
                writer,
                accumulator,
                lockRegistry,
                mock(AutoParticipantFundingBudgetService.class),
                new ResourcelessTransactionManager()
        );
        when(lockRegistry.newAcquisitionOwner()).thenReturn(SUMMARY_LOCK_OWNER);
        when(lockRegistry.tryAcquire(
                eq(ExecutionAccountDaySummaryFlushJob.JOB_NAME),
                any(),
                eq(SUMMARY_LOCK_OWNER)
        )).thenReturn(true);
        when(writer.rebuildExecutionAccountDaySummary(BUSINESS_DATE, REBUILT_AT)).thenReturn(4);
        doAnswer(invocation -> {
            IntSupplier rebuildAction = invocation.getArgument(1);
            return rebuildAction.getAsInt();
        }).when(accumulator).rebuildDate(eq(BUSINESS_DATE), any());

        int rebuilt = service.rebuildAccountDaySummary(BUSINESS_DATE, REBUILT_AT);

        assertThat(rebuilt).isEqualTo(4);
        verify(lockRegistry).release(ExecutionAccountDaySummaryFlushJob.JOB_NAME, SUMMARY_LOCK_OWNER);
    }

    @Test
    void rebuildAccountDaySummary_rebuildFails_releasesCommittedFlushLock() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        ExecutionAccountDaySummaryAccumulator accumulator = mock(ExecutionAccountDaySummaryAccumulator.class);
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        PostCloseReportAggregationUnitService service = new PostCloseReportAggregationUnitService(
                writer,
                accumulator,
                lockRegistry,
                mock(AutoParticipantFundingBudgetService.class),
                new ResourcelessTransactionManager()
        );
        when(lockRegistry.newAcquisitionOwner()).thenReturn(SUMMARY_LOCK_OWNER);
        when(lockRegistry.tryAcquire(
                eq(ExecutionAccountDaySummaryFlushJob.JOB_NAME),
                any(),
                eq(SUMMARY_LOCK_OWNER)
        )).thenReturn(true);
        doThrow(new IllegalStateException("rebuild failed"))
                .when(accumulator).rebuildDate(eq(BUSINESS_DATE), any());

        assertThatThrownBy(() -> service.rebuildAccountDaySummary(BUSINESS_DATE, REBUILT_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rebuild failed");
        verify(lockRegistry).release(ExecutionAccountDaySummaryFlushJob.JOB_NAME, SUMMARY_LOCK_OWNER);
    }

    @Test
    void expireUnusedFundingBudgetChunk_delegatesToBoundedBudgetExpiry() {
        AutoParticipantFundingBudgetService fundingBudgetService = mock(AutoParticipantFundingBudgetService.class);
        PostCloseReportAggregationUnitService service = new PostCloseReportAggregationUnitService(
                mock(MarketCloseRolloverWriter.class),
                mock(ExecutionAccountDaySummaryAccumulator.class),
                mock(BatchJobLockRegistry.class),
                fundingBudgetService,
                new ResourcelessTransactionManager()
        );
        var expected = new AutoParticipantFundingBudgetService.FundingBudgetExpiryChunk(3, 42L, false);
        when(fundingBudgetService.expireUnusedBudgetChunk(
                REBUILT_AT.toLocalDate(), REBUILT_AT, 12L, 200
        )).thenReturn(expected);

        var result = service.expireUnusedFundingBudgetChunk(
                REBUILT_AT.toLocalDate(), REBUILT_AT, 12L, 200
        );

        assertThat(result).isEqualTo(expected);
        verify(fundingBudgetService).expireUnusedBudgetChunk(
                REBUILT_AT.toLocalDate(), REBUILT_AT, 12L, 200
        );
    }
}
