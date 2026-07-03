package stock.batch.service.batch.common.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
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
            @Value("${stock.batch.virtual-price-execution.enabled:true}") boolean virtualPriceExecutionConfigured,
            @Value("${stock.batch.order-book-execution.enabled:true}") boolean orderBookExecutionConfigured,
            @Value("${stock.batch.corporate-actions.enabled:true}") boolean corporateActionsConfigured,
            @Value("${stock.batch.auto-market.enabled:true}") boolean autoMarketConfigured,
            @Value("${stock.batch.auto-market-order-expiry.enabled:true}") boolean autoMarketOrderExpiryConfigured,
            @Value("${stock.batch.listing-auto-market.enabled:true}") boolean listingAutoMarketConfigured,
            @Value("${stock.batch.auto-participant-cash-flow.enabled:true}") boolean autoParticipantCashFlowConfigured,
            @Value("${stock.batch.market-close.enabled:true}") boolean marketCloseConfigured,
            @Value("${stock.batch.settlement.enabled:true}") boolean settlementConfigured,
            @Value("${stock.batch.holding-cleanup.enabled:true}") boolean holdingCleanupConfigured
    ) {
        this.batchJobRuntimeControl = batchJobRuntimeControl;
        this.definitions = createDefinitions(
                marketDataConfigured,
                virtualPriceExecutionConfigured,
                orderBookExecutionConfigured,
                corporateActionsConfigured,
                autoMarketConfigured,
                autoMarketOrderExpiryConfigured,
                listingAutoMarketConfigured,
                autoParticipantCashFlowConfigured,
                marketCloseConfigured,
                settlementConfigured,
                holdingCleanupConfigured
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
            boolean virtualPriceExecutionConfigured,
            boolean orderBookExecutionConfigured,
            boolean corporateActionsConfigured,
            boolean autoMarketConfigured,
            boolean autoMarketOrderExpiryConfigured,
            boolean listingAutoMarketConfigured,
            boolean autoParticipantCashFlowConfigured,
            boolean marketCloseConfigured,
            boolean settlementConfigured,
            boolean holdingCleanupConfigured
    ) {
        Map<String, RuntimeDefinition> createdDefinitions = new LinkedHashMap<>();
        put(createdDefinitions, MarketDataRefreshJob.JOB_NAME, marketDataConfigured);
        put(createdDefinitions, VirtualPriceExecutionJob.JOB_NAME, virtualPriceExecutionConfigured);
        put(createdDefinitions, OrderBookExecutionJob.JOB_NAME, orderBookExecutionConfigured);
        put(createdDefinitions, CorporateActionJob.JOB_NAME, corporateActionsConfigured);
        put(createdDefinitions, AutoMarketJob.JOB_NAME, autoMarketConfigured);
        put(createdDefinitions, AutoMarketOrderExpiryJob.JOB_NAME, autoMarketOrderExpiryConfigured);
        put(createdDefinitions, ListingAutoMarketJob.JOB_NAME, listingAutoMarketConfigured);
        put(createdDefinitions, AutoParticipantCashFlowJob.JOB_NAME, autoParticipantCashFlowConfigured);
        put(createdDefinitions, MarketCloseRolloverJob.JOB_NAME, marketCloseConfigured);
        put(createdDefinitions, PortfolioSettlementJob.JOB_NAME, settlementConfigured);
        put(createdDefinitions, HoldingCleanupJob.JOB_NAME, holdingCleanupConfigured);
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
