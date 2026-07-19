package stock.batch.service.execution.biz;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.execution.reader.OrderBookExecutionReader;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;
import stock.batch.service.batch.execution.writer.OrderBookPriceWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InternalOrderBookExecutionServiceVolumeConfigurationTest {

    @Test
    void validateVolumeConfiguration_candidateScanAboveSafeMaximum_rejectsStartup() {
        InternalOrderBookExecutionService service = serviceWithDefaults();
        ReflectionTestUtils.setField(service, "buyCandidateScanLimit", 101);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("buy-candidate-scan-limit must be between 1 and 100");
    }

    @Test
    void validateVolumeConfiguration_symbolChunkDurationAboveSafeMaximum_rejectsStartup() {
        InternalOrderBookExecutionService service = serviceWithDefaults();
        ReflectionTestUtils.setField(service, "symbolChunkMaxDurationMillis", 1_001L);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbol-chunk-max-duration-ms must be between 1 and 1000");
    }

    @Test
    void validateVolumeConfiguration_boundedDefaults_acceptsStartup() {
        InternalOrderBookExecutionService service = serviceWithDefaults();

        assertThatCode(service::validateVolumeConfiguration).doesNotThrowAnyException();
    }

    private InternalOrderBookExecutionService serviceWithDefaults() {
        InternalOrderBookExecutionService service = new InternalOrderBookExecutionService(
                mock(ExecutionCostCalculator.class),
                mock(OrderBookExecutionReader.class),
                mock(OrderBookExecutionWriter.class),
                mock(OrderBookPriceWriter.class),
                mock(StockPriceRedisPublisher.class),
                mock(MarketSessionFenceService.class),
                mock(TransactionTemplate.class),
                mock(OrderBookSymbolLock.class),
                mock(OrderBookReadySymbolQueue.class),
                mock(ExecutionAccountDaySummaryAccumulator.class)
        );
        ReflectionTestUtils.setField(service, "scanLimit", 300);
        ReflectionTestUtils.setField(service, "buyCandidateScanLimit", 20);
        ReflectionTestUtils.setField(service, "symbolChunkLimit", 5);
        ReflectionTestUtils.setField(service, "symbolChunkMaxDurationMillis", 500L);
        ReflectionTestUtils.setField(service, "readySymbolFallbackScanLimit", 8);
        ReflectionTestUtils.setField(service, "deadlockRetryMaxAttempts", 3);
        ReflectionTestUtils.setField(service, "deadlockRetryBackoffMillis", 50L);
        ReflectionTestUtils.setField(service, "slowSymbolLogThresholdMillis", 1_000L);
        return service;
    }
}
