package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;

@Service
@RequiredArgsConstructor
public class PostCloseReportAggregationService {

    /**
     * One tasklet repeat commits one Spring Batch execution-context checkpoint. Each symbol still
     * runs in its own short business transaction, but a crash before the outer checkpoint can
     * replay the whole cohort. Keep this bound deliberately smaller than the number of symbols a
     * single overnight run may process so recovery never re-reads an unbounded execution range.
     */
    private static final int MAX_SYMBOL_CHUNK_SIZE = 200;
    private static final int MAX_FUNDING_BUDGET_CHUNK_SIZE = 1_000;

    private final PostCloseCycleService postCloseCycleService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final MarketCloseRolloverWriter writer;
    private final PostCloseReportAggregationUnitService unitService;

    @Value("${stock.batch.post-close.report-aggregation.symbol-chunk-size:25}")
    private int symbolChunkSize = 25;

    @Value("${stock.batch.post-close.report-aggregation.funding-budget-chunk-size:500}")
    private int fundingBudgetChunkSize = 500;

    @PostConstruct
    void validateVolumeConfiguration() {
        validateSymbolChunkSize(symbolChunkSize);
        validateFundingBudgetChunkSize(fundingBudgetChunkSize);
    }

    public ReportAggregationChunk aggregateOrderBookDailySnapshotChunk(
            long closeCycleId,
            LocalDateTime aggregatedAt,
            String afterSymbol
    ) {
        requireClosedMarket();
        PostCloseCycle cycle = requireFrozenFullMarketCycle(closeCycleId);
        long closeRunId = requireCloseRunId(cycle);
        LocalDate businessDate = cycle.businessDate();
        return aggregateSymbolChunk(
                closeCycleId,
                afterSymbol,
                symbol -> unitService.aggregateSymbolReport(
                        closeCycleId,
                        closeRunId,
                        symbol,
                        businessDate,
                        aggregatedAt
                )
        );
    }

    public ReportAggregationChunk aggregateAccountDailySnapshotChunk(
            long closeCycleId,
            LocalDateTime aggregatedAt,
            String afterSymbol
    ) {
        requireClosedMarket();
        PostCloseCycle cycle = requireFrozenFullMarketCycle(closeCycleId);
        long closeRunId = requireCloseRunId(cycle);
        LocalDate businessDate = cycle.businessDate();
        return aggregateSymbolChunk(
                closeCycleId,
                afterSymbol,
                symbol -> unitService.aggregateAccountReport(
                        closeCycleId,
                        closeRunId,
                        symbol,
                        businessDate,
                        aggregatedAt
                )
        );
    }

    public int rebuildAccountDaySummary(long closeCycleId, LocalDateTime rebuiltAt) {
        requireClosedMarket();
        PostCloseCycle cycle = requireFrozenFullMarketCycle(closeCycleId);
        return unitService.rebuildAccountDaySummary(
                cycle.businessDate(),
                rebuiltAt
        );
    }

    public int rebuildAutoParticipantPositionState(long closeCycleId, LocalDateTime rebuiltAt) {
        requireClosedMarket();
        PostCloseCycle cycle = requireFrozenFullMarketCycle(closeCycleId);
        return unitService.rebuildAutoParticipantPositionState(
                cycle.id(),
                requireCloseRunId(cycle),
                cycle.businessDate(),
                rebuiltAt
        );
    }

    public FundingBudgetExpiryChunk expireUnusedFundingBudgetChunk(
            long closeCycleId,
            LocalDateTime expiredAt,
            long afterBudgetId
    ) {
        requireClosedMarket();
        requireFrozenFullMarketCycle(closeCycleId);
        var result = unitService.expireUnusedFundingBudgetChunk(
                expiredAt.toLocalDate(),
                expiredAt,
                afterBudgetId,
                validateFundingBudgetChunkSize(fundingBudgetChunkSize)
        );
        return new FundingBudgetExpiryChunk(
                result.expiredCount(),
                result.lastBudgetId(),
                result.finished()
        );
    }

    public FundingBudgetReconciliationChunk validateFundingBudgetReconciliationChunk(
            long closeCycleId,
            long afterBudgetId
    ) {
        requireClosedMarket();
        requireFrozenFullMarketCycle(closeCycleId);
        var result = unitService.validateFundingBudgetReconciliationChunk(
                afterBudgetId,
                validateFundingBudgetChunkSize(fundingBudgetChunkSize)
        );
        return new FundingBudgetReconciliationChunk(
                result.processedCount(),
                result.lastBudgetId(),
                result.finished()
        );
    }

    private PostCloseCycle requireFrozenFullMarketCycle(long closeCycleId) {
        PostCloseCycle cycle = postCloseCycleService.findById(closeCycleId)
                .orElseThrow(() -> new IllegalStateException("Post-close cycle does not exist: " + closeCycleId));
        if (cycle.closeRunId() == null) {
            throw new IllegalStateException("Post-close cycle has no frozen close run: " + closeCycleId);
        }
        if (cycle.phase() != PostClosePhase.OVERNIGHT_CASH_APPLIED) {
            throw new IllegalStateException(
                    "Post-close report aggregation requires OVERNIGHT_CASH_APPLIED: cycleId=%d, phase=%s"
                            .formatted(closeCycleId, cycle.phase())
            );
        }
        return cycle;
    }

    private long requireCloseRunId(PostCloseCycle cycle) {
        Long closeRunId = cycle.closeRunId();
        if (closeRunId == null) {
            throw new IllegalStateException("Post-close cycle has no close run: " + cycle.id());
        }
        return closeRunId;
    }

    private void requireClosedMarket() {
        if (marketSessionFenceService.hasOpenMarket()) {
            throw new IllegalStateException(
                    "Post-close report aggregation cannot run while any enabled market is open"
            );
        }
    }

    private ReportAggregationChunk aggregateSymbolChunk(
            long closeCycleId,
            String afterSymbol,
            SymbolAggregator aggregator
    ) {
        int chunkSize = validateSymbolChunkSize(symbolChunkSize);
        int processed = 0;
        String normalizedAfterSymbol = afterSymbol == null ? "" : afterSymbol;
        List<String> symbols = writer.findCloseReportSymbolChunk(
                closeCycleId,
                normalizedAfterSymbol,
                chunkSize
        );
        for (String symbol : symbols) {
            processed += aggregator.aggregate(symbol);
        }
        String lastSymbol = symbols.isEmpty() ? normalizedAfterSymbol : symbols.getLast();
        return new ReportAggregationChunk(processed, lastSymbol, symbols.size() < chunkSize);
    }

    static int validateSymbolChunkSize(int chunkSize) {
        if (chunkSize < 1 || chunkSize > MAX_SYMBOL_CHUNK_SIZE) {
            throw new IllegalStateException(
                    "stock.batch.post-close.report-aggregation.symbol-chunk-size must be between 1 and %d: %d"
                            .formatted(MAX_SYMBOL_CHUNK_SIZE, chunkSize)
            );
        }
        return chunkSize;
    }

    static int validateFundingBudgetChunkSize(int chunkSize) {
        if (chunkSize < 1 || chunkSize > MAX_FUNDING_BUDGET_CHUNK_SIZE) {
            throw new IllegalStateException(
                    "stock.batch.post-close.report-aggregation.funding-budget-chunk-size must be between 1 and %d: %d"
                            .formatted(MAX_FUNDING_BUDGET_CHUNK_SIZE, chunkSize)
            );
        }
        return chunkSize;
    }

    @FunctionalInterface
    private interface SymbolAggregator {
        int aggregate(String symbol);
    }

    public record ReportAggregationChunk(int processedCount, String lastSymbol, boolean finished) {

        public ReportAggregationChunk {
            if (processedCount < 0) {
                throw new IllegalArgumentException("processedCount must not be negative");
            }
            lastSymbol = lastSymbol == null ? "" : lastSymbol;
        }
    }

    public record FundingBudgetReconciliationChunk(
            int processedCount,
            long lastBudgetId,
            boolean finished
    ) {
    }

    public record FundingBudgetExpiryChunk(
            int expiredCount,
            long lastBudgetId,
            boolean finished
    ) {
    }
}
