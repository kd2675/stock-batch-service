package stock.batch.service.marketclose.biz;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostClosePhaseExecutionService {

    private final PostCloseCycleService postCloseCycleService;

    public boolean execute(
            PostCloseCycle cycle,
            PostClosePhase expectedPhase,
            PostClosePhase nextPhase,
            Supplier<StockBatchJobRunResponse> jobAction
    ) {
        if (cycle.phase() != expectedPhase) {
            return cycle.phase().ordinal() > expectedPhase.ordinal();
        }
        LocalDateTime claimedAt = LocalDateTime.now();
        if (!postCloseCycleService.isPhaseClaimEligible(cycle, claimedAt)) {
            return false;
        }
        PostClosePhaseClaim claim = postCloseCycleService.tryClaim(cycle.id(), expectedPhase, claimedAt)
                .orElse(null);
        if (claim == null) {
            return false;
        }
        try {
            StockBatchJobRunResponse response = jobAction.get();
            if (isCompleted(response)) {
                postCloseCycleService.completeClaim(claim, nextPhase, LocalDateTime.now());
                return true;
            }
            if (response != null && "SKIPPED".equals(response.status())) {
                postCloseCycleService.deferPhase(claim, response.message(), LocalDateTime.now());
                return false;
            }
            if (response != null
                    && response.processedCount() > 0
                    && supportsBoundedContinuation(expectedPhase)) {
                postCloseCycleService.continuePhaseAfterProgress(
                        claim,
                        response.processedCount(),
                        response.message(),
                        LocalDateTime.now()
                );
                return false;
            }
            RuntimeException failure = new IllegalStateException(
                    response == null ? "Post-close phase returned no response" : response.message()
            );
            postCloseCycleService.failPhase(claim, failure, LocalDateTime.now());
            return false;
        } catch (RuntimeException failure) {
            postCloseCycleService.failPhase(claim, failure, LocalDateTime.now());
            log.warn(
                    "Post-close phase execution failed: cycleId={}, phase={}, nextPhase={}, reason={}",
                    cycle.id(),
                    expectedPhase,
                    nextPhase,
                    failure.getMessage(),
                    failure
            );
            return false;
        }
    }

    private boolean isCompleted(StockBatchJobRunResponse response) {
        return response != null
                && ("COMPLETED".equals(response.status())
                || StockBatchJobRunResponses.isAlreadyCompleteSkip(response));
    }

    /**
     * Only the two corporate-action phases deliberately fail their final validation after a
     * bounded cohort committed. Other jobs can also expose a positive Spring Batch write count
     * before a real failure (for example, one report symbol committed before the next symbol
     * failed). Treating those as bounded progress would re-read the same large ledger range every
     * base retry interval and could compete with the next regular session. They must use normal
     * exponential failure backoff instead.
     */
    private boolean supportsBoundedContinuation(PostClosePhase phase) {
        return phase == PostClosePhase.OVERNIGHT_CASH_APPLIED
                || phase == PostClosePhase.REPORTS_AGGREGATED;
    }
}
