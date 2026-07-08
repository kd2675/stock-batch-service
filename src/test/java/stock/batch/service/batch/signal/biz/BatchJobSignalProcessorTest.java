package stock.batch.service.batch.signal.biz;

import org.junit.jupiter.api.Test;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchJobSignalProcessorTest {

    private final BatchJobSignalReader signalReader = mock(BatchJobSignalReader.class);
    private final BatchJobSignalWriter signalWriter = mock(BatchJobSignalWriter.class);
    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final BatchJobSignalProcessor processor = new BatchJobSignalProcessor(
            signalReader,
            signalWriter,
            stockBatchJobLauncher
    );

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
        when(signalReader.claimNext()).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.fundAutoParticipantsManually()).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(stockBatchJobLauncher).fundAutoParticipantsManually();
        verify(signalWriter).complete(10L, response);
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
        when(signalReader.claimNext()).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.cancelOpenOrderBookOrders("DEMO001")).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(stockBatchJobLauncher).cancelOpenOrderBookOrders("DEMO001");
        verify(signalWriter).complete(10L, response);
    }

    @Test
    void processPendingSignals_skippedTargetJob_defersSignalForRetryAndStopsCurrentChunk() {
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
                "Job is already running",
                now,
                now
        );
        when(signalReader.claimNext()).thenReturn(Optional.of(signal));
        when(stockBatchJobLauncher.rolloverClosingPrices()).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isZero();
        verify(signalWriter).defer(10L, response);
        verify(signalWriter, never()).complete(10L, response);
    }

    @Test
    void processPendingSignals_emptyQueueDoesNotCheckActiveJobs() {
        when(signalReader.claimNext()).thenReturn(Optional.empty());

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isZero();
        verifyNoInteractions(signalWriter);
    }
}
