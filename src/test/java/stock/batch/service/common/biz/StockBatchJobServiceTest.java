package stock.batch.service.common.biz;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;
import stock.batch.service.execution.biz.OrderExecutionService;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;
import stock.batch.service.settlement.biz.PortfolioSettlementService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockBatchJobServiceTest {

    private final MarketDataRefreshService marketDataRefreshService = mock(MarketDataRefreshService.class);
    private final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
    private final InternalOrderBookExecutionService internalOrderBookExecutionService = mock(InternalOrderBookExecutionService.class);
    private final PortfolioSettlementService portfolioSettlementService = mock(PortfolioSettlementService.class);

    private final StockBatchJobService stockBatchJobService = new StockBatchJobService(
            marketDataRefreshService,
            orderExecutionService,
            internalOrderBookExecutionService,
            portfolioSettlementService
    );

    @Test
    void executePendingOrders_virtualMarketPrice_usesMarketPriceExecution() {
        ReflectionTestUtils.setField(stockBatchJobService, "executionMode", "virtual-market-price");
        when(orderExecutionService.executeEligibleOrders()).thenReturn(3);

        var response = stockBatchJobService.executePendingOrders();

        assertThat(response.executionMode()).isEqualTo("virtual-market-price");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isEqualTo(3);
        assertThat(response.message()).isEqualTo("Job completed");
        verify(orderExecutionService).executeEligibleOrders();
        verify(internalOrderBookExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void executePendingOrders_internalOrderBook_usesOrderBookExecution() {
        ReflectionTestUtils.setField(stockBatchJobService, "executionMode", "internal-order-book");
        when(internalOrderBookExecutionService.executeEligibleOrders()).thenReturn(2);

        var response = stockBatchJobService.executePendingOrders();

        assertThat(response.executionMode()).isEqualTo("internal-order-book");
        assertThat(response.processedCount()).isEqualTo(2);
        verify(internalOrderBookExecutionService).executeEligibleOrders();
        verify(orderExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void executePendingOrders_unknownExecutionMode_throwsException() {
        ReflectionTestUtils.setField(stockBatchJobService, "executionMode", "order-book");

        assertThatThrownBy(stockBatchJobService::executePendingOrders)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported stock batch execution mode");
        verify(orderExecutionService, never()).executeEligibleOrders();
        verify(internalOrderBookExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void validateExecutionMode_unknownExecutionMode_throwsException() {
        ReflectionTestUtils.setField(stockBatchJobService, "executionMode", "order-book");

        assertThatThrownBy(stockBatchJobService::validateExecutionMode)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported stock batch execution mode");
    }

    @Test
    void refreshMarketData_manualRun_invokesMarketDataRefresh() {
        when(marketDataRefreshService.refreshWatchedPrices()).thenReturn(5);

        var response = stockBatchJobService.refreshMarketData();

        assertThat(response.job()).isEqualTo("market-data-refresh");
        assertThat(response.processedCount()).isEqualTo(5);
        verify(marketDataRefreshService).refreshWatchedPrices();
    }

    @Test
    void settlePortfolios_manualRun_invokesPortfolioSettlement() {
        when(portfolioSettlementService.settleToday()).thenReturn(4);

        var response = stockBatchJobService.settlePortfolios();

        assertThat(response.job()).isEqualTo("portfolio-settlement");
        assertThat(response.processedCount()).isEqualTo(4);
        verify(portfolioSettlementService).settleToday();
    }

    @Test
    void executePendingOrders_sameJobAlreadyRunning_skipsSecondRun() throws Exception {
        ReflectionTestUtils.setField(stockBatchJobService, "executionMode", "virtual-market-price");
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        doAnswer(invocation -> {
            actionStarted.countDown();
            assertThat(releaseAction.await(3, TimeUnit.SECONDS)).isTrue();
            return 3;
        }).when(orderExecutionService).executeEligibleOrders();

        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstRun = executor.submit(stockBatchJobService::executePendingOrders);
            assertThat(actionStarted.await(3, TimeUnit.SECONDS)).isTrue();

            var secondResponse = stockBatchJobService.executePendingOrders();

            assertThat(secondResponse.status()).isEqualTo("SKIPPED");
            assertThat(secondResponse.processedCount()).isZero();
            assertThat(secondResponse.message()).isEqualTo("Job is already running");
            releaseAction.countDown();
            assertThat(firstRun.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            verify(orderExecutionService, times(1)).executeEligibleOrders();
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refreshMarketData_actionThrows_returnsFailedResponse() {
        doThrow(new IllegalStateException("provider unavailable"))
                .when(marketDataRefreshService)
                .refreshWatchedPrices();

        var response = stockBatchJobService.refreshMarketData();

        assertThat(response.job()).isEqualTo("market-data-refresh");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).contains("provider unavailable");
    }
}
