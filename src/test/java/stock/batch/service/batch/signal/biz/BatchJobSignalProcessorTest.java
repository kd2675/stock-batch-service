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
    void processPendingSignals_activeBatchJob_defersWithoutClaimingSignal() {
        when(stockBatchJobLauncher.hasActiveJobs()).thenReturn(true);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isZero();
        verify(signalReader, never()).claimNext();
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
        when(stockBatchJobLauncher.hasActiveJobs()).thenReturn(false);
        when(signalReader.claimNext()).thenReturn(Optional.of(signal)).thenReturn(Optional.empty());
        when(stockBatchJobLauncher.cancelOpenOrderBookOrders("DEMO001")).thenReturn(response);

        int processedCount = processor.processPendingSignals(20);

        assertThat(processedCount).isEqualTo(1);
        verify(stockBatchJobLauncher).cancelOpenOrderBookOrders("DEMO001");
        verify(signalWriter).complete(10L, response);
    }
}
