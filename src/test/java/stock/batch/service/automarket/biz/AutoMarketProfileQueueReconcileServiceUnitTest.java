package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.queue.AutoMarketReadyProfileQueue;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

class AutoMarketProfileQueueReconcileServiceUnitTest {

    @Test
    void validateVolumeConfiguration_reconcileLimitAboveSafeMaximum_rejectsStartup() {
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                mock(AutoMarketReader.class),
                mock(AutoProfileBehaviorSupport.class),
                mock(AutoParticipantOrderScheduleService.class),
                mock(AutoMarketReadyProfileQueue.class),
                mock(SimulationClockService.class),
                mock(TransactionTemplate.class)
        );
        ReflectionTestUtils.setField(service, "reconcileLimit", 1_001);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconcile-limit must be between 1 and 1000");
    }

    @Test
    void reconcileReadyProfiles_upsertsDatabaseProfileSchedulesIntoRedisQueue() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                profileBehaviorSupport,
                scheduleService,
                readyProfileQueue,
                simulationClockService,
                transactionTemplate
        );
        ReflectionTestUtils.setField(service, "reconcileLimit", 100);
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 9, 0);
        List<AutoMarketConfig> configs = List.of(autoMarketConfig("DEMO001"));
        List<AutoMarketReadyProfileQueue.ReadyProfile> readyProfiles = List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.MARKET_MAKER, now),
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, now.plusSeconds(3))
        );
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findEnabledConfigs()).thenReturn(configs);
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());
        when(autoMarketReader.hasMissingParticipantSchedules(configs)).thenReturn(false);
        when(scheduleService.findNextProfileSchedules(now, 100)).thenReturn(readyProfiles);
        when(readyProfileQueue.enqueueAll(readyProfiles)).thenReturn(2);

        int enqueuedCount = service.reconcileReadyProfiles();

        assertThat(enqueuedCount).isEqualTo(2);
        verify(scheduleService).findNextProfileSchedules(now, 100);
        verify(readyProfileQueue).removeAll(argThat(profileTypes ->
                profileTypes.size() == AutoParticipantProfileType.values().length - readyProfiles.size()
                        && !profileTypes.contains(AutoParticipantProfileType.MARKET_MAKER)
                        && !profileTypes.contains(AutoParticipantProfileType.SCALPER)
        ));
        verify(readyProfileQueue).enqueueAll(readyProfiles);
    }

    @Test
    void reconcileReadyProfiles_withoutEnabledConfig_removesAllQueuedProfiles() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                profileBehaviorSupport,
                scheduleService,
                readyProfileQueue,
                simulationClockService,
                transactionTemplate
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 9, 0);
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of());

        int enqueuedCount = service.reconcileReadyProfiles();

        assertThat(enqueuedCount).isZero();
        verify(readyProfileQueue).removeAll(argThat(profileTypes ->
                profileTypes.size() == AutoParticipantProfileType.values().length
        ));
    }

    @Test
    void reconcileReadyProfilesForPreOpen_replacesCompleteQueueStrictly() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                profileBehaviorSupport,
                scheduleService,
                readyProfileQueue,
                simulationClockService,
                mock(TransactionTemplate.class)
        );
        ReflectionTestUtils.setField(service, "reconcileLimit", 100);
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 5, 30);
        List<AutoMarketConfig> configs = List.of(autoMarketConfig("DEMO001"));
        List<AutoMarketReadyProfileQueue.ReadyProfile> readyProfiles = List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.MARKET_MAKER, now),
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, now.plusSeconds(3))
        );
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findDailyRegimePreCreateConfigs()).thenReturn(configs);
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());
        when(autoMarketReader.hasMissingParticipantSchedules(configs)).thenReturn(false);
        when(scheduleService.findNextProfileSchedules(now, 100)).thenReturn(readyProfiles);
        when(readyProfileQueue.replaceAll(readyProfiles)).thenReturn(2);

        int storedCount = service.reconcileReadyProfilesForPreOpen();

        assertThat(storedCount).isEqualTo(2);
        verify(readyProfileQueue).replaceAll(readyProfiles);
    }

    @Test
    void reconcileReadyProfilesForPreOpen_partialQueueReplacement_failsPhase() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                profileBehaviorSupport,
                scheduleService,
                readyProfileQueue,
                simulationClockService,
                mock(TransactionTemplate.class)
        );
        ReflectionTestUtils.setField(service, "reconcileLimit", 100);
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 5, 30);
        List<AutoMarketConfig> configs = List.of(autoMarketConfig("DEMO001"));
        List<AutoMarketReadyProfileQueue.ReadyProfile> readyProfiles = List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.MARKET_MAKER, now),
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, now.plusSeconds(3))
        );
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findDailyRegimePreCreateConfigs()).thenReturn(configs);
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());
        when(autoMarketReader.hasMissingParticipantSchedules(configs)).thenReturn(false);
        when(scheduleService.findNextProfileSchedules(now, 100)).thenReturn(readyProfiles);
        when(readyProfileQueue.replaceAll(readyProfiles)).thenReturn(1);

        assertThatThrownBy(service::reconcileReadyProfilesForPreOpen)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=2, stored=1");
    }

    @Test
    void reconcileReadyProfilesForPreOpen_withoutConfig_strictlyClearsQueue() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                mock(AutoProfileBehaviorSupport.class),
                mock(AutoParticipantOrderScheduleService.class),
                readyProfileQueue,
                simulationClockService,
                mock(TransactionTemplate.class)
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 5, 30);
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findDailyRegimePreCreateConfigs()).thenReturn(List.of());
        when(readyProfileQueue.replaceAll(List.of())).thenReturn(0);

        int storedCount = service.reconcileReadyProfilesForPreOpen();

        assertThat(storedCount).isZero();
        verify(readyProfileQueue).replaceAll(List.of());
    }

    @Test
    void isPreOpenQueueSynchronized_exactBoundedProjection_returnsTrue() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                mock(AutoProfileBehaviorSupport.class),
                scheduleService,
                readyProfileQueue,
                simulationClockService,
                mock(TransactionTemplate.class)
        );
        ReflectionTestUtils.setField(service, "reconcileLimit", 100);
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 5, 30);
        LocalDateTime readyAt = now.plusSeconds(3).plusNanos(456_000);
        List<AutoMarketConfig> configs = List.of(autoMarketConfig("DEMO001"));
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findDailyRegimePreCreateConfigs()).thenReturn(configs);
        when(autoMarketReader.hasMissingParticipantSchedules(configs)).thenReturn(false);
        when(scheduleService.findNextProfileSchedules(now, 100)).thenReturn(List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, readyAt)
        ));
        when(readyProfileQueue.snapshot()).thenReturn(Map.of(
                AutoParticipantProfileType.SCALPER,
                readyAt.withNano(456_000)
        ));

        assertThat(service.isPreOpenQueueSynchronized()).isTrue();
    }

    @Test
    void isPreOpenQueueSynchronized_missingRedisProfile_returnsFalse() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoMarketReadyProfileQueue readyProfileQueue = mock(AutoMarketReadyProfileQueue.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        AutoMarketProfileQueueReconcileService service = new AutoMarketProfileQueueReconcileService(
                autoMarketReader,
                mock(AutoProfileBehaviorSupport.class),
                scheduleService,
                readyProfileQueue,
                simulationClockService,
                mock(TransactionTemplate.class)
        );
        ReflectionTestUtils.setField(service, "reconcileLimit", 100);
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 5, 30);
        List<AutoMarketConfig> configs = List.of(autoMarketConfig("DEMO001"));
        when(simulationClockService.currentSnapshot()).thenReturn(clock(now));
        when(autoMarketReader.findDailyRegimePreCreateConfigs()).thenReturn(configs);
        when(autoMarketReader.hasMissingParticipantSchedules(configs)).thenReturn(false);
        when(scheduleService.findNextProfileSchedules(now, 100)).thenReturn(List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, now.plusSeconds(3))
        ));
        when(readyProfileQueue.snapshot()).thenReturn(Map.of());

        assertThat(service.isPreOpenQueueSynchronized()).isFalse();
    }

    private AutoMarketConfig autoMarketConfig(String symbol) {
        return new AutoMarketConfig(
                symbol,
                100,
                90,
                1_000_000L,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN,
                null
        );
    }

    private SimulationClockSnapshot clock(LocalDateTime now) {
        return new SimulationClockSnapshot(
                LocalDate.from(now),
                now,
                now.toLocalDate().atStartOfDay(),
                now,
                now.toLocalDate().atStartOfDay(),
                7200,
                true,
                true,
                0,
                null,
                null
        );
    }
}
