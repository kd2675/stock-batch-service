package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

class ListingAutoMarketJobServiceTest {

    @Test
    void runListingAutoMarket_pausedSimulationClockSkipsListingOrdersEvenDuringRegularSession() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        ListingAutoAccountOrderService listingAutoAccountOrderService = mock(ListingAutoAccountOrderService.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        ListingAutoMarketJobService service = new ListingAutoMarketJobService(
                autoMarketReader,
                listingAutoAccountOrderService,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                now.toLocalDate().atStartOfDay(),
                now,
                now.toLocalDate().atStartOfDay(),
                7200,
                false,
                false,
                0,
                null,
                null
        ));
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);

        int processed = service.runListingAutoMarket();

        assertThat(processed).isZero();
        verify(autoMarketReader, never()).findEnabledConfigs();
        verify(listingAutoAccountOrderService, never()).run(org.mockito.Mockito.any());
    }

    @Test
    void runListingAutoMarket_deadlockRetriesListingSymbolInNewTransaction() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        ListingAutoAccountOrderService listingAutoAccountOrderService = mock(ListingAutoAccountOrderService.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = transactionTemplate();
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        ListingAutoMarketJobService service = new ListingAutoMarketJobService(
                autoMarketReader,
                listingAutoAccountOrderService,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock
        );
        ReflectionTestUtils.setField(service, "deadlockRetryBackoffMs", 0L);
        AutoMarketConfig config = config("LST001");
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(simulationClockService.currentSnapshot()).thenReturn(runningSnapshot(now));
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(listingAutoAccountOrderService.run(config))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(3);

        int processed = service.runListingAutoMarket();

        assertThat(processed).isEqualTo(3);
        verify(transactionTemplate, times(2)).execute(any());
        verify(listingAutoAccountOrderService, times(2)).run(config);
    }

    private AutoMarketConfig config(String symbol) {
        return new AutoMarketConfig(
                symbol,
                5,
                100,
                90,
                100000L,
                BigDecimal.ONE,
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                BigDecimal.valueOf(30),
                null
        );
    }

    private SimulationClockSnapshot runningSnapshot(LocalDateTime now) {
        return new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                now.toLocalDate().atStartOfDay(),
                now,
                now.toLocalDate().atStartOfDay(),
                7200,
                true,
                false,
                0,
                now,
                now
        );
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactionTemplate;
    }
}
