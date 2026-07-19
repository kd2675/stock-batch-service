package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleKind;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;
import stock.batch.service.marketclose.model.PostCloseScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostClosePhaseExecutionServiceTest {

    private PostCloseCycleService postCloseCycleService;
    private PostClosePhaseExecutionService service;
    private PostCloseCycle cycle;
    private PostClosePhaseClaim claim;

    @BeforeEach
    void setUp() {
        postCloseCycleService = mock(PostCloseCycleService.class);
        service = new PostClosePhaseExecutionService(postCloseCycleService);
        cycle = cycle(PostClosePhase.PORTFOLIO_SETTLED);
        claim = new PostClosePhaseClaim(
                cycle.id(),
                PostClosePhase.PORTFOLIO_SETTLED,
                2,
                "owner",
                LocalDateTime.now().plusMinutes(3)
        );
        when(postCloseCycleService.tryClaim(
                eq(cycle.id()),
                eq(PostClosePhase.PORTFOLIO_SETTLED),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(claim));
        when(postCloseCycleService.isPhaseClaimEligible(eq(cycle), any(LocalDateTime.class)))
                .thenReturn(true);
    }

    @Test
    void execute_completedResponse_advancesClaimExactlyOnce() {
        boolean advanced = service.execute(
                cycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> response("COMPLETED", "Job completed")
        );

        assertThat(advanced).isTrue();
        verify(postCloseCycleService).completeClaim(
                eq(claim),
                eq(PostClosePhase.OVERNIGHT_CASH_APPLIED),
                any(LocalDateTime.class)
        );
    }

    @Test
    void execute_alreadyCompleteResponse_advancesRestartedPhase() {
        boolean advanced = service.execute(
                cycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> response("SKIPPED", StockBatchJobRunResponses.ALREADY_COMPLETE_MESSAGE)
        );

        assertThat(advanced).isTrue();
        verify(postCloseCycleService).completeClaim(
                eq(claim),
                eq(PostClosePhase.OVERNIGHT_CASH_APPLIED),
                any(LocalDateTime.class)
        );
    }

    @Test
    void execute_deferredResponse_releasesClaimWithoutAdvancing() {
        boolean advanced = service.execute(
                cycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> response("SKIPPED", "Scheduled job is disabled")
        );

        assertThat(advanced).isFalse();
        verify(postCloseCycleService).deferPhase(
                eq(claim),
                eq("Scheduled job is disabled"),
                any(LocalDateTime.class)
        );
        verify(postCloseCycleService, never()).completeClaim(any(), any(), any());
    }

    @Test
    void execute_failedResponse_recordsFailureWithoutThrowingFromCoordinatorPoll() {
        boolean advanced = service.execute(
                cycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> response("FAILED", "cash flow failed")
        );

        assertThat(advanced).isFalse();
        verify(postCloseCycleService).failPhase(
                eq(claim),
                any(IllegalStateException.class),
                any(LocalDateTime.class)
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = PostClosePhase.class,
            names = {"OVERNIGHT_CASH_APPLIED", "REPORTS_AGGREGATED"}
    )
    void execute_corporatePhaseFailureWithCommittedProgress_usesBaseContinuationInsteadOfFailureBackoff(
            PostClosePhase corporatePhase
    ) {
        PostCloseCycle corporateCycle = cycle(corporatePhase);
        PostClosePhaseClaim corporateClaim = new PostClosePhaseClaim(
                corporateCycle.id(),
                corporatePhase,
                2,
                "owner",
                LocalDateTime.now().plusMinutes(3)
        );
        when(postCloseCycleService.tryClaim(
                eq(corporateCycle.id()),
                eq(corporatePhase),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(corporateClaim));
        when(postCloseCycleService.isPhaseClaimEligible(eq(corporateCycle), any(LocalDateTime.class)))
                .thenReturn(true);

        boolean advanced = service.execute(
                corporateCycle,
                corporatePhase,
                corporatePhase == PostClosePhase.OVERNIGHT_CASH_APPLIED
                        ? PostClosePhase.CORPORATE_CASH_APPLIED
                        : PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED,
                () -> response("FAILED", 200, "more bounded work remains")
        );

        assertThat(advanced).isFalse();
        verify(postCloseCycleService).continuePhaseAfterProgress(
                eq(corporateClaim),
                eq(200),
                eq("more bounded work remains"),
                any(LocalDateTime.class)
        );
        verify(postCloseCycleService, never()).failPhase(any(), any(), any());
    }

    @Test
    void execute_nonCorporateFailureWithPositiveWriteCount_usesExponentialFailureBackoff() {
        boolean advanced = service.execute(
                cycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> response("FAILED", 200, "report or settlement failed after a partial write")
        );

        assertThat(advanced).isFalse();
        verify(postCloseCycleService).failPhase(
                eq(claim),
                any(IllegalStateException.class),
                any(LocalDateTime.class)
        );
        verify(postCloseCycleService, never()).continuePhaseAfterProgress(any(), anyInt(), any(), any());
    }

    @Test
    void execute_phaseAlreadyAdvanced_doesNotClaimOrRerunJob() {
        PostCloseCycle advancedCycle = cycle(PostClosePhase.CORPORATE_CASH_APPLIED);

        boolean advanced = service.execute(
                advancedCycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> {
                    throw new AssertionError("job must not run");
                }
        );

        assertThat(advanced).isTrue();
        verify(postCloseCycleService, never()).tryClaim(anyLong(), any(), any());
    }

    @Test
    void execute_retryWindowNotReached_doesNotClaimOrWriteBatchMetadata() {
        PostCloseCycle backedOffCycle = cycle(
                PostClosePhase.PORTFOLIO_SETTLED,
                LocalDateTime.now().plusMinutes(1)
        );
        when(postCloseCycleService.isPhaseClaimEligible(eq(backedOffCycle), any(LocalDateTime.class)))
                .thenReturn(false);

        boolean advanced = service.execute(
                backedOffCycle,
                PostClosePhase.PORTFOLIO_SETTLED,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                () -> {
                    throw new AssertionError("job must not run before nextRetryAt");
                }
        );

        assertThat(advanced).isFalse();
        verify(postCloseCycleService, never()).tryClaim(anyLong(), any(), any());
    }

    private PostCloseCycle cycle(PostClosePhase phase) {
        return cycle(phase, null);
    }

    private PostCloseCycle cycle(PostClosePhase phase, LocalDateTime nextRetryAt) {
        return new PostCloseCycle(
                41L,
                LocalDate.of(2026, 7, 1),
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostCloseCycleKind.TRADING,
                null,
                phase,
                PostCloseCycleStatus.PENDING,
                1,
                0,
                91L,
                LocalDateTime.of(2026, 7, 1, 18, 10),
                1,
                null,
                null,
                nextRetryAt
        );
    }

    private StockBatchJobRunResponse response(String status, String message) {
        return response(status, 0, message);
    }

    private StockBatchJobRunResponse response(String status, int processedCount, String message) {
        LocalDateTime now = LocalDateTime.now();
        return new StockBatchJobRunResponse(
                "test-job",
                status,
                "scheduled",
                processedCount,
                message,
                now,
                now
        );
    }
}
