package stock.batch.service.marketdata.biz;

import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;

import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MarketDataRefreshTransactionExecutorTest {

    @Test
    void execute_postCloseMutation_checksLedgerPermitBeforePriceWrite() {
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        Runnable action = mock(Runnable.class);
        MarketDataRefreshTransactionExecutor executor = new MarketDataRefreshTransactionExecutor(
                new ResourcelessTransactionManager(),
                fenceService
        );

        executor.execute(action, true);

        var ordered = inOrder(fenceService, action);
        ordered.verify(fenceService).acquireMarketLedgerMutationPermit("pre-open market data refresh");
        ordered.verify(action).run();
    }

    @Test
    void execute_regularRefresh_usesCloseBoundaryPermitBeforePriceWrite() {
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        Runnable action = mock(Runnable.class);
        MarketDataRefreshTransactionExecutor executor = new MarketDataRefreshTransactionExecutor(
                new ResourcelessTransactionManager(),
                fenceService
        );

        executor.execute(action, false);

        var ordered = inOrder(fenceService, action);
        ordered.verify(fenceService).acquireLiveMarketDataMutationPermit("compatibility market data refresh");
        ordered.verify(action).run();
        verify(fenceService, never()).acquireMarketLedgerMutationPermit("pre-open market data refresh");
    }
}
