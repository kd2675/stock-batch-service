package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
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
        verify(readyProfileQueue).enqueueAll(readyProfiles);
    }

    private AutoMarketConfig autoMarketConfig(String symbol) {
        return new AutoMarketConfig(
                symbol,
                5,
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
