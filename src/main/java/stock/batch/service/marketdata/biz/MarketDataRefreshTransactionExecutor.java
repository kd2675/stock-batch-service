package stock.batch.service.marketdata.biz;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
class MarketDataRefreshTransactionExecutor {

    private final TransactionTemplate transactionTemplate;
    private final MarketSessionFenceService marketSessionFenceService;

    MarketDataRefreshTransactionExecutor(
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            MarketSessionFenceService marketSessionFenceService
    ) {
        this.marketSessionFenceService = marketSessionFenceService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    void execute(Runnable action, boolean postCloseMutation) {
        transactionTemplate.executeWithoutResult(status -> {
            if (postCloseMutation) {
                marketSessionFenceService.acquireMarketLedgerMutationPermit("pre-open market data refresh");
            } else {
                marketSessionFenceService.acquireLiveMarketDataMutationPermit(
                        "compatibility market data refresh"
                );
            }
            action.run();
        });
    }
}
