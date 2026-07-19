package stock.batch.service.batch.common.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoMarketPreOpenProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.ExecutionAccountDaySummaryFlushJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketclose.job.MarketOpenReadinessJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.metadata.job.BatchMetadataRetentionJob;
import stock.batch.service.batch.report.job.PostCloseReportAggregationJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.BatchJobRuntimeStatusResponse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BatchJobRuntimeCatalog {

    private final BatchJobRuntimeControl batchJobRuntimeControl;
    private final Map<String, RuntimeDefinition> definitions;

    public BatchJobRuntimeCatalog(
            BatchJobRuntimeControl batchJobRuntimeControl,
            @Value("${stock.batch.market-data.enabled:true}") boolean marketDataConfigured,
            @Value("${stock.batch.order-book-execution.enabled:true}") boolean orderBookExecutionConfigured,
            @Value("${stock.batch.corporate-actions.enabled:true}") boolean corporateActionsConfigured,
            @Value("${stock.batch.auto-market.enabled:true}") boolean autoMarketConfigured,
            @Value("${stock.batch.auto-market.daily-regime.enabled:true}") boolean autoMarketDailyRegimeConfigured,
            @Value("${stock.batch.auto-market.profile-queue.reconcile-enabled:true}") boolean autoMarketProfileQueueReconcileConfigured,
            @Value("${stock.batch.auto-market-order-expiry.enabled:true}") boolean autoMarketOrderExpiryConfigured,
            @Value("${stock.batch.listing-auto-market.enabled:true}") boolean listingAutoMarketConfigured,
            @Value("${stock.batch.auto-participant-cash-flow.enabled:true}") boolean autoParticipantCashFlowConfigured,
            @Value("${stock.batch.market-close.enabled:true}") boolean marketCloseConfigured,
            @Value("${stock.batch.settlement.enabled:true}") boolean settlementConfigured,
            @Value("${stock.batch.post-close.report-aggregation.enabled:true}") boolean postCloseReportAggregationConfigured,
            @Value("${stock.batch.post-close.readiness.enabled:true}") boolean marketOpenReadinessConfigured,
            @Value("${stock.batch.holding-cleanup.enabled:true}") boolean holdingCleanupConfigured,
            @Value("${stock.batch.metadata-retention.enabled:false}") boolean metadataRetentionConfigured
    ) {
        this.batchJobRuntimeControl = batchJobRuntimeControl;
        this.definitions = createDefinitions(
                marketDataConfigured,
                orderBookExecutionConfigured,
                corporateActionsConfigured,
                autoMarketConfigured,
                autoMarketDailyRegimeConfigured,
                autoMarketProfileQueueReconcileConfigured,
                autoMarketOrderExpiryConfigured,
                listingAutoMarketConfigured,
                autoParticipantCashFlowConfigured,
                marketCloseConfigured,
                settlementConfigured,
                postCloseReportAggregationConfigured,
                marketOpenReadinessConfigured,
                holdingCleanupConfigured,
                metadataRetentionConfigured
        );
    }

    public List<BatchJobRuntimeStatusResponse> statuses() {
        return definitions.values().stream()
                .map(this::status)
                .toList();
    }

    public BatchJobRuntimeStatusResponse status(String jobName) {
        return status(requireDefinition(jobName));
    }

    public BatchJobRuntimeStatusResponse update(String jobName, boolean runtimeEnabled, String updatedBy) {
        RuntimeDefinition definition = requireDefinition(jobName);
        return toResponse(batchJobRuntimeControl.update(
                definition.jobName(),
                definition.schedulerConfigured(),
                runtimeEnabled,
                updatedBy
        ));
    }

    private BatchJobRuntimeStatusResponse status(RuntimeDefinition definition) {
        return toResponse(batchJobRuntimeControl.status(
                definition.jobName(),
                definition.schedulerConfigured()
        ));
    }

    private RuntimeDefinition requireDefinition(String jobName) {
        String normalizedJobName = BatchJobNames.normalize(jobName);
        RuntimeDefinition definition = definitions.get(normalizedJobName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown batch job: " + normalizedJobName);
        }
        return definition;
    }

    private BatchJobRuntimeStatusResponse toResponse(BatchJobRuntimeStatus status) {
        return new BatchJobRuntimeStatusResponse(
                status.jobName(),
                status.schedulerConfigured(),
                status.runtimeEnabled(),
                status.effectiveEnabled(),
                status.updatedBy(),
                status.updatedAt()
        );
    }

    private Map<String, RuntimeDefinition> createDefinitions(
            boolean marketDataConfigured,
            boolean orderBookExecutionConfigured,
            boolean corporateActionsConfigured,
            boolean autoMarketConfigured,
            boolean autoMarketDailyRegimeConfigured,
            boolean autoMarketProfileQueueReconcileConfigured,
            boolean autoMarketOrderExpiryConfigured,
            boolean listingAutoMarketConfigured,
            boolean autoParticipantCashFlowConfigured,
            boolean marketCloseConfigured,
            boolean settlementConfigured,
            boolean postCloseReportAggregationConfigured,
            boolean marketOpenReadinessConfigured,
            boolean holdingCleanupConfigured,
            boolean metadataRetentionConfigured
    ) {
        Map<String, RuntimeDefinition> createdDefinitions = new LinkedHashMap<>();
        put(createdDefinitions, MarketDataRefreshJob.JOB_NAME, marketDataConfigured);
        put(createdDefinitions, OrderBookExecutionJob.JOB_NAME, orderBookExecutionConfigured);
        put(createdDefinitions, ExecutionAccountDaySummaryFlushJob.JOB_NAME, orderBookExecutionConfigured);
        put(createdDefinitions, CorporateActionJob.JOB_NAME, corporateActionsConfigured);
        put(createdDefinitions, AutoMarketJob.JOB_NAME, autoMarketConfigured);
        put(createdDefinitions, AutoMarketDailyRegimePreCreateJob.JOB_NAME, autoMarketDailyRegimeConfigured);
        put(createdDefinitions, AutoMarketProfileQueueReconcileJob.JOB_NAME, autoMarketProfileQueueReconcileConfigured);
        put(createdDefinitions, AutoMarketPreOpenProfileQueueReconcileJob.JOB_NAME, autoMarketProfileQueueReconcileConfigured);
        put(createdDefinitions, AutoMarketOrderExpiryJob.JOB_NAME, autoMarketOrderExpiryConfigured);
        put(createdDefinitions, ListingAutoMarketJob.JOB_NAME, listingAutoMarketConfigured);
        put(createdDefinitions, AutoParticipantCashFlowJob.JOB_NAME, autoParticipantCashFlowConfigured);
        put(createdDefinitions, MarketCloseRolloverJob.JOB_NAME, marketCloseConfigured);
        put(createdDefinitions, PortfolioSettlementJob.JOB_NAME, settlementConfigured);
        put(createdDefinitions, PostCloseReportAggregationJob.JOB_NAME, postCloseReportAggregationConfigured);
        put(createdDefinitions, MarketOpenReadinessJob.JOB_NAME, marketOpenReadinessConfigured);
        put(createdDefinitions, HoldingCleanupJob.JOB_NAME, holdingCleanupConfigured);
        put(createdDefinitions, BatchMetadataRetentionJob.JOB_NAME, metadataRetentionConfigured);
        return Collections.unmodifiableMap(createdDefinitions);
    }

    private void put(Map<String, RuntimeDefinition> definitions, String jobName, boolean schedulerConfigured) {
        definitions.put(jobName, new RuntimeDefinition(jobName, schedulerConfigured));
    }

    private record RuntimeDefinition(
            String jobName,
            boolean schedulerConfigured
    ) {
    }
}
