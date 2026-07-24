package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.batch.execution.job.ExecutionAccountDaySummaryFlushJob;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;
import stock.batch.service.execution.biz.ExecutionAccountDaySummaryAccumulator;

@Service
class PostCloseReportAggregationUnitService {

    private final MarketCloseRolloverWriter writer;
    private final ExecutionAccountDaySummaryAccumulator summaryAccumulator;
    private final BatchJobLockRegistry batchJobLockRegistry;
    private final AutoParticipantFundingBudgetService fundingBudgetService;
    private final TransactionTemplate requiresNewTransaction;

    PostCloseReportAggregationUnitService(
            MarketCloseRolloverWriter writer,
            ExecutionAccountDaySummaryAccumulator summaryAccumulator,
            BatchJobLockRegistry batchJobLockRegistry,
            AutoParticipantFundingBudgetService fundingBudgetService,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager
    ) {
        this.writer = writer;
        this.summaryAccumulator = summaryAccumulator;
        this.batchJobLockRegistry = batchJobLockRegistry;
        this.fundingBudgetService = fundingBudgetService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int aggregateSymbolReport(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDate businessDate,
            LocalDateTime aggregatedAt
    ) {
        LocalDateTime rangeStart = businessDate.atStartOfDay();
        LocalDateTime rangeEnd = businessDate.plusDays(1).atStartOfDay();
        // Replace only this symbol inside the same short transaction. If the Spring Batch
        // execution-context commit fails after this business transaction commits, restarting the
        // last symbol is idempotent and never deletes already completed symbols.
        writer.deleteOrderBookDailySnapshot(closeRunId, symbol);
        int inserted = writer.snapshotOrderBookDailySymbols(
                closeCycleId,
                closeRunId,
                symbol,
                businessDate,
                aggregatedAt,
                rangeStart,
                rangeEnd
        );
        writer.updateClosePriceLastExecutionId(
                closeCycleId,
                symbol,
                rangeStart,
                rangeEnd
        );
        return inserted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int aggregateAccountReport(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDate businessDate,
            LocalDateTime aggregatedAt
    ) {
        writer.deleteDailyAccountExecutionSnapshot(closeRunId, symbol);
        return writer.snapshotDailyAccountExecutions(
                closeCycleId,
                closeRunId,
                symbol,
                businessDate,
                aggregatedAt,
                businessDate.atStartOfDay(),
                businessDate.plusDays(1).atStartOfDay()
        );
    }

    public int rebuildAccountDaySummary(
            LocalDate businessDate,
            LocalDateTime rebuiltAt
    ) {
        String lockName = ExecutionAccountDaySummaryFlushJob.JOB_NAME;
        String lockOwner = batchJobLockRegistry.newAcquisitionOwner();
        boolean lockAcquired = Boolean.TRUE.equals(requiresNewTransaction.execute(
                status -> batchJobLockRegistry.tryAcquire(lockName, LocalDateTime.now(), lockOwner)
        ));
        if (!lockAcquired) {
            throw new CannotAcquireLockException(
                    "Execution account-day summary flush is active; post-close rebuild will retry"
            );
        }
        try {
            Integer rebuiltCount = requiresNewTransaction.execute(status -> summaryAccumulator.rebuildDate(
                            businessDate,
                            () -> writer.rebuildExecutionAccountDaySummary(businessDate, rebuiltAt)
                    )
            );
            if (rebuiltCount == null) {
                throw new IllegalStateException("Execution account-day summary rebuild returned no result");
            }
            return rebuiltCount;
        } finally {
            requiresNewTransaction.executeWithoutResult(
                    status -> batchJobLockRegistry.release(lockName, lockOwner)
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rebuildAutoParticipantPositionState(
            long closeCycleId,
            long closeRunId,
            LocalDate businessDate,
            LocalDateTime rebuiltAt
    ) {
        return writer.rebuildAutoParticipantPositionState(
                closeCycleId,
                closeRunId,
                businessDate,
                rebuiltAt
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoParticipantFundingBudgetService.FundingBudgetExpiryChunk expireUnusedFundingBudgetChunk(
            LocalDate businessDate,
            LocalDateTime expiredAt,
            long afterBudgetId,
            int chunkSize
    ) {
        return fundingBudgetService.expireUnusedBudgetChunk(
                businessDate,
                expiredAt,
                afterBudgetId,
                chunkSize
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AutoParticipantFundingBudgetService.FundingBudgetReconciliationChunk validateFundingBudgetReconciliationChunk(
            long afterBudgetId,
            int chunkSize
    ) {
        return fundingBudgetService.validateReconciliationChunk(afterBudgetId, chunkSize);
    }
}
