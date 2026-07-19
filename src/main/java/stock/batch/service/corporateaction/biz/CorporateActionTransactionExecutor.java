package stock.batch.service.corporateaction.biz;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
class CorporateActionTransactionExecutor {

    private final TransactionTemplate transactionTemplate;
    private final MarketSessionFenceService marketSessionFenceService;

    CorporateActionTransactionExecutor(
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            MarketSessionFenceService marketSessionFenceService
    ) {
        this.marketSessionFenceService = marketSessionFenceService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    int execute(Supplier<Integer> action) {
        Integer result = transactionTemplate.execute(status -> {
            acquireLedgerMutationPermit();
            return action.get();
        });
        return result == null ? 0 : result;
    }

    <T> T executeValue(Supplier<T> action) {
        return transactionTemplate.execute(status -> {
            acquireLedgerMutationPermit();
            return action.get();
        });
    }

    private void acquireLedgerMutationPermit() {
        marketSessionFenceService.acquireMarketLedgerMutationPermit("corporate action");
    }
}
