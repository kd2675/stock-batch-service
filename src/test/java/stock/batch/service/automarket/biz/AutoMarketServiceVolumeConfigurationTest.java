package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.Executor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.config.AutoMarketGenerationSlotLimiter;
import stock.batch.service.automarket.lock.AutoMarketProfileLock;
import stock.batch.service.automarket.queue.AutoMarketReadyProfileQueue;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

class AutoMarketServiceVolumeConfigurationTest {

    @Test
    void validateVolumeConfiguration_participantChunkAboveLimit_throws() {
        AutoMarketService service = serviceWithLimits(101, 100);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-participant-chunk-size must be between 1 and 100");
    }

    @Test
    void validateVolumeConfiguration_dueLimitAboveLimit_throws() {
        AutoMarketService service = serviceWithLimits(25, 501);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-due-limit-per-symbol must be between 1 and 500");
    }

    @Test
    void validateVolumeConfiguration_boundedValues_doesNotThrow() {
        AutoMarketService service = serviceWithLimits(100, 500);

        assertThatCode(service::validateVolumeConfiguration).doesNotThrowAnyException();
    }

    @Test
    void generationDueParticipantLimit_multipleSymbolsAndProfilesKeepsWholeRunWithinBudget() {
        AutoMarketService service = serviceWithLimits(25, 100);
        ReflectionTestUtils.setField(service, "generationCandidateRowLimit", 2_000);

        Integer participantLimit = ReflectionTestUtils.invokeMethod(
                service,
                "generationDueParticipantLimit",
                100,
                9
        );

        assertThat(participantLimit).isEqualTo(2);
    }

    @Test
    void generationReadyProfileLimit_manySymbolsCapsClaimedProfilesByRowBudget() {
        AutoMarketService service = serviceWithLimits(25, 100);
        ReflectionTestUtils.setField(service, "generationCandidateRowLimit", 2_000);

        Integer profileLimit = ReflectionTestUtils.invokeMethod(
                service,
                "generationReadyProfileLimit",
                500
        );

        assertThat(profileLimit).isEqualTo(4);
    }

    @Test
    void hasCandidateCapacity_activeSymbolsAboveBudget_rejectsCombinationQuery() {
        AutoMarketService service = serviceWithLimits(25, 100);
        ReflectionTestUtils.setField(service, "generationCandidateRowLimit", 2_000);

        Boolean hasCapacity = ReflectionTestUtils.invokeMethod(service, "hasCandidateCapacity", 2_001);

        assertThat(hasCapacity).isFalse();
    }

    @Test
    void validateVolumeConfiguration_candidateRowLimitAboveLimit_throws() {
        AutoMarketService service = serviceWithLimits(25, 100);
        ReflectionTestUtils.setField(service, "generationCandidateRowLimit", 10_001);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-candidate-row-limit must be between 1 and 10000");
    }

    @Test
    void validateVolumeConfiguration_profileWorkerCountAboveLimit_throws() {
        AutoMarketService service = serviceWithLimits(25, 100);
        ReflectionTestUtils.setField(service, "generationProfileWorkerCount", 17);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation-profile-worker-count must be between 1 and 16");
    }

    private AutoMarketService serviceWithLimits(int participantChunkSize, int dueLimitPerSymbol) {
        Executor directExecutor = Runnable::run;
        AutoMarketService service = new AutoMarketService(
                mock(AutoMarketReader.class),
                mock(AutoMarketDailyRegimeService.class),
                mock(AutoParticipantOrderService.class),
                mock(AutoParticipantOrderScheduleService.class),
                mock(AutoProfileBehaviorSupport.class),
                mock(SimulationClockService.class),
                mock(SimulationMarketSessionService.class),
                mock(TransactionTemplate.class),
                mock(AutoMarketProfileLock.class),
                mock(AutoMarketReadyProfileQueue.class),
                directExecutor,
                new AutoMarketGenerationSlotLimiter(1),
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "generationParticipantChunkSize", participantChunkSize);
        ReflectionTestUtils.setField(service, "generationDueLimitPerSymbol", dueLimitPerSymbol);
        ReflectionTestUtils.setField(service, "generationCandidateRowLimit", 2_000);
        ReflectionTestUtils.setField(service, "generationProfileWorkerCount", 9);
        return service;
    }
}
