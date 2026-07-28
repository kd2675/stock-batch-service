package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionOrderIntentExecutionServiceTest {

    @Test
    void runPendingIntents_transientLockFailureDoesNotConsumeBusinessFailureAttempts() {
        InstitutionOrderIntentRepository repository = mock(InstitutionOrderIntentRepository.class);
        InstitutionOrderIntentProcessor processor = mock(InstitutionOrderIntentProcessor.class);
        AutoMarketOrderExecutor orderExecutor = mock(AutoMarketOrderExecutor.class);
        OrderBookSymbolLock symbolLock = mock(OrderBookSymbolLock.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        OrderBookSymbolLock.LockHandle lockHandle = mock(OrderBookSymbolLock.LockHandle.class);
        InstitutionOrderIntentRepository.IntentReference reference =
                new InstitutionOrderIntentRepository.IntentReference(101L, "DEMO001");
        LocalDate tradeDate = LocalDate.of(2027, 1, 27);
        LocalDateTime observedAt = tradeDate.atTime(10, 0);
        AutoMarketConfig config = mock(AutoMarketConfig.class);

        when(repository.findPendingIntents(tradeDate, 20)).thenReturn(java.util.List.of(reference));
        when(symbolLock.tryLock("DEMO001")).thenReturn(Optional.of(lockHandle));
        when(transactionTemplate.execute(any()))
                .thenThrow(new CannotAcquireLockException("temporary deadlock"));

        InstitutionOrderIntentExecutionService service =
                new InstitutionOrderIntentExecutionService(
                        repository,
                        processor,
                        orderExecutor,
                        symbolLock,
                        fenceService,
                        transactionTemplate,
                        20,
                        3,
                        0L
                );

        int submitted = service.runPendingIntents(
                Map.of("DEMO001", config),
                tradeDate,
                observedAt
        );

        assertThat(submitted).isZero();
        verify(transactionTemplate, times(4)).execute(any());
        verify(repository, never()).recordFailure(any(), any(), any());
    }
}
