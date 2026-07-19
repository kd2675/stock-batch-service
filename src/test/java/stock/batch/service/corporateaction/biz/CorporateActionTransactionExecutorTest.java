package stock.batch.service.corporateaction.biz;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;

import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorporateActionTransactionExecutorTest {

    @Test
    void execute_permitGranted_checksFreezeBeforeCorporateAction() {
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        @SuppressWarnings("unchecked")
        Supplier<Integer> action = mock(Supplier.class);
        when(action.get()).thenReturn(7);
        CorporateActionTransactionExecutor executor = new CorporateActionTransactionExecutor(
                new ResourcelessTransactionManager(),
                fenceService
        );

        int result = executor.execute(action);

        assertThat(result).isEqualTo(7);
        var ordered = inOrder(fenceService, action);
        ordered.verify(fenceService).acquireMarketLedgerMutationPermit("corporate action");
        ordered.verify(action).get();
    }

    @Test
    void executeValue_freezePending_rejectsBeforeCorporateAction() {
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        @SuppressWarnings("unchecked")
        Supplier<String> action = mock(Supplier.class);
        CorporateActionTransactionExecutor executor = new CorporateActionTransactionExecutor(
                new ResourcelessTransactionManager(),
                fenceService
        );
        IllegalStateException failure = new IllegalStateException("ledger freeze is in progress");
        org.mockito.Mockito.doThrow(failure)
                .when(fenceService)
                .acquireMarketLedgerMutationPermit("corporate action");

        assertThatThrownBy(() -> executor.executeValue(action))
                .isSameAs(failure);
        verify(action, never()).get();
    }
}
