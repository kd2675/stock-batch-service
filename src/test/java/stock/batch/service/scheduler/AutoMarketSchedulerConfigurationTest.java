package stock.batch.service.scheduler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.Executor;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationMarketSessionService;

class AutoMarketSchedulerConfigurationTest {

    @ParameterizedTest
    @MethodSource("unsafeIntervals")
    void validateVolumeConfiguration_pollingIntervalBelowSafeMinimum_rejectsStartup(
            String fieldName,
            long value,
            String propertyName
    ) {
        AutoMarketScheduler scheduler = scheduler();
        ReflectionTestUtils.setField(scheduler, fieldName, value);

        assertThatThrownBy(scheduler::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(propertyName);
    }

    private static Stream<Arguments> unsafeIntervals() {
        return Stream.of(
                Arguments.of("autoMarketFixedRateMillis", 4_999L, "stock.batch.auto-market.fixed-rate-ms"),
                Arguments.of(
                        "orderExpiryFixedDelayMillis",
                        4_999L,
                        "stock.batch.auto-market-order-expiry.fixed-delay-ms"
                ),
                Arguments.of(
                        "listingAutoMarketFixedDelayMillis",
                        4_999L,
                        "stock.batch.listing-auto-market.fixed-delay-ms"
                ),
                Arguments.of(
                        "profileQueueReconcileFixedDelayMillis",
                        59_999L,
                        "stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms"
                )
        );
    }

    private AutoMarketScheduler scheduler() {
        Executor directExecutor = Runnable::run;
        return new AutoMarketScheduler(
                mock(StockBatchJobLauncher.class),
                mock(StockBatchScheduledJobGuard.class),
                mock(SimulationMarketSessionService.class),
                mock(MarketSessionFenceService.class),
                mock(AutoMarketDailyRegimePreCreateService.class),
                mock(AutoMarketProfileQueueReconcileService.class),
                directExecutor
        );
    }
}
