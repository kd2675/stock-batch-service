package stock.batch.service.batch.signal.biz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.simulation.SimulationClockService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchJobSignalProcessorTest {

    @Test
    void validateChunkLimit_aboveOperationalLimit_rejectsConfiguration() {
        assertThatThrownBy(() -> BatchJobSignalProcessor.validateChunkLimit(101))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 100");
    }

    private final BatchJobSignalReader signalReader = mock(BatchJobSignalReader.class);
    private final BatchJobSignalWriter signalWriter = mock(BatchJobSignalWriter.class);
    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final SimulationClockService simulationClockService = mock(SimulationClockService.class);
    private final BatchJobSignalValidationService signalValidationService = mock(BatchJobSignalValidationService.class);
    private final BatchJobSignalLeaseHeartbeat signalLeaseHeartbeat = mock(BatchJobSignalLeaseHeartbeat.class);
    private final BatchJobSignalProcessor processor = new BatchJobSignalProcessor(
            signalReader,
            signalWriter,
            stockBatchJobLauncher,
            simulationClockService,
            signalValidationService,
            signalLeaseHeartbeat
    );

    @BeforeEach
    void setUp() {
        when(signalReader.hasClaimable(any(LocalDateTime.class))).thenReturn(true);
        when(signalLeaseHeartbeat.execute(any(BatchJobSignal.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(1, Supplier.class);
            return action.get();
        });
    }

    @Test
    void processPendingSignals_activeBatchJobStillClaimsAndRunsSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "AUTO_PARTICIPANT_CASH_FLOW_RUN",
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                null,
                "admin",
                now
        );
        StockBatchJobRunResponse response = new StockBatchJobRunResponse(
                "auto-participant-cash-flow",
                "COMPLETED",
                "manual-recurring-cash",
                1,
                "Job completed",
                now,
                now
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class))).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.fundAutoParticipantsManually(now.toLocalDate(), null, 10L)).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(stockBatchJobLauncher).fundAutoParticipantsManually(now.toLocalDate(), null, 10L);
        verify(signalWriter).complete(signal, response);
    }

    @Test
    void processPendingSignals_manualCashFlowCarriesRequestedCycleContext() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 4, 0, 10);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "AUTO_PARTICIPANT_CASH_FLOW_RUN",
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                null,
                "admin",
                now.minusHours(6),
                now.toLocalDate().minusDays(1),
                null,
                501L,
                now,
                now,
                1,
                12,
                "claim-10",
                now.plusMinutes(3)
        );
        StockBatchJobRunResponse response = new StockBatchJobRunResponse(
                "auto-participant-cash-flow",
                "COMPLETED",
                "manual-recurring-cash",
                1,
                "Job completed",
                now,
                now
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class)))
                .thenReturn(Optional.of(signal))
                .thenReturn(Optional.empty());
        when(stockBatchJobLauncher.fundAutoParticipantsManually(
                signal.requestedBusinessDate(),
                501L,
                10L
        )).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(stockBatchJobLauncher).fundAutoParticipantsManually(
                signal.requestedBusinessDate(),
                501L,
                10L
        );
    }

    @Test
    void processPendingSignals_noActiveBatchJob_claimsAndRunsSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL",
                "market-close-rollover",
                "halt-open-order-cancel:DEMO001",
                "DEMO001",
                "admin",
                now
        );
        StockBatchJobRunResponse response = new StockBatchJobRunResponse(
                "market-close-rollover",
                "COMPLETED",
                "halt-open-order-cancel:DEMO001",
                3,
                "Job completed",
                now,
                now
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class))).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.cancelOpenOrderBookOrders("DEMO001", now.toLocalDate(), null, 10L))
                .thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(stockBatchJobLauncher).cancelOpenOrderBookOrders("DEMO001", now.toLocalDate(), null, 10L);
        verify(signalWriter).complete(signal, response);
    }

    @Test
    void processPendingSignals_skippedTargetJob_defersAndContinuesWithNextSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "MARKET_CLOSE_ROLLOVER_RUN",
                "market-close-rollover",
                "price-limit-base",
                null,
                "admin",
                now
        );
        BatchJobSignal nextSignal = new BatchJobSignal(
                11L,
                "MARKET_CLOSE_ROLLOVER_RUN",
                "market-close-rollover",
                "price-limit-base",
                null,
                "admin",
                now.plusSeconds(1)
        );
        StockBatchJobRunResponse response = new StockBatchJobRunResponse(
                "market-close-rollover",
                "SKIPPED",
                "price-limit-base",
                0,
                "Job is already running",
                now,
                now
        );
        StockBatchJobRunResponse nextResponse = new StockBatchJobRunResponse(
                "market-close-rollover",
                "COMPLETED",
                "price-limit-base",
                2,
                "Job completed",
                now,
                now
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class)))
                .thenReturn(Optional.of(signal))
                .thenReturn(Optional.of(nextSignal))
                .thenReturn(Optional.empty());
        when(stockBatchJobLauncher.rolloverClosingPrices(now.toLocalDate(), null, null, 10L)).thenReturn(response);
        when(stockBatchJobLauncher.rolloverClosingPrices(now.toLocalDate(), null, null, 11L)).thenReturn(nextResponse);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isOne();
        verify(signalWriter).defer(signal, response);
        verify(signalWriter, never()).complete(signal, response);
        verify(signalWriter).complete(nextSignal, nextResponse);
    }

    @Test
    void processPendingSignals_manualCashFlowSkippedBecauseAutoEnabled_completesSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "AUTO_PARTICIPANT_CASH_FLOW_RUN",
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                null,
                "admin",
                now
        );
        StockBatchJobRunResponse response = StockBatchJobRunResponses.manualCashFlowAutoEnabled(now);
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class))).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.fundAutoParticipantsManually(now.toLocalDate(), null, 10L)).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(signalWriter).complete(signal, response);
        verify(signalWriter, never()).defer(signal, response);
    }

    @Test
    void processPendingSignals_jobInstanceAlreadyComplete_completesIdempotentSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "MARKET_CLOSE_ROLLOVER_RUN",
                "market-close-rollover",
                "price-limit-base",
                null,
                "admin",
                now
        );
        StockBatchJobRunResponse response = new StockBatchJobRunResponse(
                "market-close-rollover",
                "SKIPPED",
                "price-limit-base",
                0,
                StockBatchJobRunResponses.ALREADY_COMPLETE_MESSAGE,
                now,
                now
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class))).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.rolloverClosingPrices(now.toLocalDate(), null, null, 10L)).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(signalWriter).complete(signal, response);
        verify(signalWriter, never()).defer(signal, response);
    }

    @Test
    void processPendingSignals_manualCashFlowDuringRegularSession_defersSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "AUTO_PARTICIPANT_CASH_FLOW_RUN",
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                null,
                "admin",
                now
        );
        StockBatchJobRunResponse response = StockBatchJobRunResponses.manualCashFlowBeforeMarketClose(now);
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class))).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.fundAutoParticipantsManually(now.toLocalDate(), null, 10L)).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isZero();
        verify(signalWriter).defer(signal, response);
        verify(signalWriter, never()).complete(signal, response);
    }

    @Test
    void processPendingSignals_emptyQueueSkipsTransactionalClaim() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.hasClaimable(now)).thenReturn(false);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isZero();
        verify(signalReader, never()).claimNext(any(LocalDateTime.class));
        verifyNoInteractions(signalWriter);
    }

    @Test
    void deadLetterExhaustedSignals_runsOnlyDedicatedSweep() {
        when(signalReader.deadLetterExhaustedLeases(any(LocalDateTime.class))).thenReturn(2);

        assertThat(processor.deadLetterExhaustedSignals()).isEqualTo(2);
    }

    @Test
    void processPendingSignals_claimLostDuringJob_doesNotOverwriteNewOwner() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 11, 0);
        BatchJobSignal signal = new BatchJobSignal(
                10L,
                "MARKET_CLOSE_ROLLOVER_RUN",
                "market-close-rollover",
                "price-limit-base",
                null,
                "admin",
                now
        );
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(signalReader.claimNext(any(LocalDateTime.class)))
                .thenReturn(Optional.of(signal))
                .thenReturn(Optional.empty());
        when(signalLeaseHeartbeat.execute(any(BatchJobSignal.class), any()))
                .thenThrow(new BatchJobSignalClaimLostException(10L));

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isZero();
        verifyNoInteractions(signalWriter);
    }
}
