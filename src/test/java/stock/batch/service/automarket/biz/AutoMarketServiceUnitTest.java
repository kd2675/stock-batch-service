package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantSymbolStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import web.common.core.simulation.SimulationClockSnapshot;

class AutoMarketServiceUnitTest {

    @Test
    void runAutoMarketStep_orderGenerationFailureDoesNotCompleteClaimedSchedules() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        Executor directExecutor = Runnable::run;
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                directExecutor
        );
        ReflectionTestUtils.setField(service, "generationDueLimitPerSymbol", 100);
        ReflectionTestUtils.setField(service, "generationParticipantChunkSize", 25);
        ReflectionTestUtils.setField(service, "slowSymbolLogThresholdMs", Long.MAX_VALUE);

        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        SimulationClockSnapshot clock = new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                true,
                false,
                0,
                now,
                now
        );
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER,
                null,
                null,
                null
        );
        ProfilePolicy noisePolicy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = Map.of(AutoParticipantProfileType.NOISE_TRADER, noisePolicy);
        when(simulationClockService.currentSnapshot()).thenReturn(clock);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(profilePolicies);
        when(profileBehaviorSupport.policy(profilePolicies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(noisePolicy);
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), now, 100))
                .thenReturn(List.of(new AutoParticipantSymbolStrategy("STOCK001", strategy, now.minusSeconds(1), 50)));
        when(autoMarketReader.hasMissingParticipantSchedules(List.of(config))).thenReturn(false);
        when(autoMarketReader.findLatestPricesAtOrBefore(List.of("STOCK001"), now.minusHours(1)))
                .thenReturn(Map.of("STOCK001", new BigDecimal("70000.00")));
        when(scheduleService.claimDueStrategies(List.of(strategy), profilePolicies, now, false))
                .thenReturn(List.of(strategy));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(autoParticipantOrderService.placeAutoOrders(List.of(strategy), config, profilePolicies, 0.0))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(scheduleService, never()).completeStrategies(any(), any(), any());
    }

    @Test
    void runAutoMarketStep_missingSchedulesSeedsSchedulesOnlyOnBootstrapPath() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        Executor directExecutor = Runnable::run;
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                directExecutor
        );
        ReflectionTestUtils.setField(service, "generationDueLimitPerSymbol", 100);
        ReflectionTestUtils.setField(service, "generationParticipantChunkSize", 25);
        ReflectionTestUtils.setField(service, "slowSymbolLogThresholdMs", Long.MAX_VALUE);

        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        SimulationClockSnapshot clock = new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                true,
                false,
                0,
                now,
                now
        );
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER,
                null,
                null,
                null
        );
        ProfilePolicy noisePolicy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = Map.of(AutoParticipantProfileType.NOISE_TRADER, noisePolicy);
        when(simulationClockService.currentSnapshot()).thenReturn(clock);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(profilePolicies);
        when(autoMarketReader.hasMissingParticipantSchedules(List.of(config))).thenReturn(true);
        when(autoMarketReader.findEnabledParticipantStrategiesBySymbol(List.of(config)))
                .thenReturn(Map.of("STOCK001", List.of(strategy)));
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), now, 100))
                .thenReturn(List.of());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(scheduleService).ensureSchedules(List.of(strategy), profilePolicies, now);
        verify(scheduleService, never()).claimDueStrategies(any(), any(), any(), eq(true));
        verify(autoParticipantOrderService, never()).placeAutoOrders(any(), any(), any(), anyDouble());
    }

    @Test
    void runAutoMarketStep_orderGenerationDeadlockRetriesChunkTransaction() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        Executor directExecutor = Runnable::run;
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                directExecutor
        );
        ReflectionTestUtils.setField(service, "generationDueLimitPerSymbol", 100);
        ReflectionTestUtils.setField(service, "generationParticipantChunkSize", 25);
        ReflectionTestUtils.setField(service, "deadlockRetryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "deadlockRetryBackoffMs", 0L);
        ReflectionTestUtils.setField(service, "slowSymbolLogThresholdMs", Long.MAX_VALUE);

        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        SimulationClockSnapshot clock = new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                true,
                false,
                0,
                now,
                now
        );
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER,
                null,
                null,
                null
        );
        ProfilePolicy noisePolicy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = Map.of(AutoParticipantProfileType.NOISE_TRADER, noisePolicy);
        when(simulationClockService.currentSnapshot()).thenReturn(clock);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(profilePolicies);
        when(profileBehaviorSupport.policy(profilePolicies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(noisePolicy);
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), now, 100))
                .thenReturn(List.of(new AutoParticipantSymbolStrategy("STOCK001", strategy, now.minusSeconds(1), 50)));
        when(autoMarketReader.hasMissingParticipantSchedules(List.of(config))).thenReturn(false);
        when(autoMarketReader.findLatestPricesAtOrBefore(List.of("STOCK001"), now.minusHours(1)))
                .thenReturn(Map.of("STOCK001", new BigDecimal("70000.00")));
        when(scheduleService.claimDueStrategies(List.of(strategy), profilePolicies, now, false))
                .thenReturn(List.of(strategy));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(autoParticipantOrderService.placeAutoOrders(List.of(strategy), config, profilePolicies, 0.0))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(new AutoParticipantOrderGenerationResult(1, 1, 0, 0));

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isEqualTo(1);
        verify(autoParticipantOrderService, times(2)).placeAutoOrders(List.of(strategy), config, profilePolicies, 0.0);
        verify(scheduleService, times(1)).completeStrategies(List.of(strategy), profilePolicies, now);
    }

    @Test
    void runAutoMarketStep_pausedSimulationClockSkipsGenerationEvenDuringRegularSession() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                Runnable::run
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        SimulationClockSnapshot clock = new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                now,
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                false,
                false,
                0,
                null,
                null
        );
        when(simulationClockService.currentSnapshot()).thenReturn(clock);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(autoMarketReader, never()).findEnabledConfigs();
        verify(transactionTemplate, never()).execute(any());
    }
}
