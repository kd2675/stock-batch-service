package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import stock.batch.service.automarket.config.AutoMarketGenerationSlotLimiter;
import stock.batch.service.automarket.lock.AutoMarketProfileLock;
import stock.batch.service.automarket.queue.AutoMarketReadyProfileQueue;
import stock.batch.service.automarket.queue.InMemoryAutoMarketReadyProfileQueue;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

class AutoMarketServiceUnitTest {

    @Test
    void runAutoMarketStep_withoutDueProfile_skipsAllAutoMarketDatabaseReads() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 16, 0);
        SimulationClockSnapshot clock = new SimulationClockSnapshot(
                now.toLocalDate(),
                now,
                now.toLocalDate().atStartOfDay(),
                now,
                now.toLocalDate().atStartOfDay(),
                7200,
                true,
                false,
                0,
                now,
                now
        );
        when(simulationClockService.currentSnapshot()).thenReturn(clock);
        when(simulationMarketSessionService.sessionAt(now)).thenReturn(SimulationMarketSession.REGULAR);
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                mock(AutoParticipantOrderService.class),
                mock(AutoParticipantOrderScheduleService.class),
                mock(AutoProfileBehaviorSupport.class),
                simulationClockService,
                simulationMarketSessionService,
                mock(TransactionTemplate.class),
                profileType -> Optional.empty(),
                new InMemoryAutoMarketReadyProfileQueue(),
                Runnable::run,
                new AutoMarketGenerationSlotLimiter(12),
                new SimpleMeterRegistry()
        );

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(autoMarketReader, never()).findEnabledConfigs();
        verify(autoMarketReader, never()).findParticipantProfileConfigs();
        verify(autoMarketDailyRegimeService, never()).applyDailyRegimes(any(), any(), any());
    }

    @Test
    void claimReadyProfiles_claimsOnlyRedisReadyProfiles() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        InMemoryReadyProfileQueue readyProfileQueue = new InMemoryReadyProfileQueue();
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 16, 0);
        readyProfileQueue.enqueue(AutoParticipantProfileType.MARKET_MAKER, now.minusSeconds(1));
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                profileType -> Optional.of(() -> {
                }),
                readyProfileQueue,
                Runnable::run,
                new AutoMarketGenerationSlotLimiter(12),
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "generationProfileWorkerCount", 1);

        List<?> profiles = ReflectionTestUtils.invokeMethod(service, "claimReadyProfiles", now, 1);

        assertThat(profiles).hasSize(1);
        assertThat(ReflectionTestUtils.getField(profiles.get(0), "profileType"))
                .isEqualTo(AutoParticipantProfileType.MARKET_MAKER);
        verify(scheduleService, never()).findDueProfileSchedules(any(), anyInt());
    }

    @Test
    void claimReadyProfiles_withoutGenerationSlot_doesNotClaimRedisProfile() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        InMemoryReadyProfileQueue readyProfileQueue = new InMemoryReadyProfileQueue();
        AutoMarketGenerationSlotLimiter generationSlotLimiter = new AutoMarketGenerationSlotLimiter(1);
        assertThat(generationSlotLimiter.tryAcquire()).isTrue();
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 16, 0);
        readyProfileQueue.enqueue(AutoParticipantProfileType.MARKET_MAKER, now.minusSeconds(1));
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                profileType -> Optional.of(() -> {
                }),
                readyProfileQueue,
                Runnable::run,
                generationSlotLimiter,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "generationProfileWorkerCount", 1);

        List<?> profiles = ReflectionTestUtils.invokeMethod(service, "claimReadyProfiles", now, 1);

        assertThat(profiles).isEmpty();
        assertThat(readyProfileQueue.readyAtByProfile)
                .containsKey(AutoParticipantProfileType.MARKET_MAKER);
        verify(scheduleService, never()).findDueProfileSchedules(any(), anyInt());
    }

    @Test
    void runAutoMarketStep_orderGenerationFailureDoesNotCompleteClaimedSchedules() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileLock autoMarketProfileLock = profileType -> Optional.of(() -> {
        });
        Executor directExecutor = Runnable::run;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                autoMarketProfileLock,
                readyProfileQueue(AutoParticipantProfileType.NOISE_TRADER),
                directExecutor,
                new AutoMarketGenerationSlotLimiter(12),
                meterRegistry
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
        when(simulationMarketSessionService.sessionAt(clock.simulationDateTime()))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketDailyRegimeService.applyDailyRegimes(any(), eq(now.toLocalDate()), eq(now)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(profilePolicies);
        when(profileBehaviorSupport.policy(profilePolicies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(noisePolicy);
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), AutoParticipantProfileType.NOISE_TRADER, now, 100))
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
        when(autoParticipantOrderService.placeAutoOrders(List.of(strategy), config, profilePolicies, 0.0, now))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(scheduleService, never()).completeStrategies(any(), any(), any());
    }

    @Test
    void runAutoMarketStep_claimedProfileWithoutCandidates_isRequeued() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileLock autoMarketProfileLock = profileType -> Optional.of(() -> {
        });
        InMemoryReadyProfileQueue readyProfileQueue = new InMemoryReadyProfileQueue();
        Executor directExecutor = Runnable::run;
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                autoMarketProfileLock,
                readyProfileQueue,
                directExecutor,
                new AutoMarketGenerationSlotLimiter(12),
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "generationProfileWorkerCount", 1);
        ReflectionTestUtils.setField(service, "generationDueLimitPerSymbol", 100);
        ReflectionTestUtils.setField(service, "slowSymbolLogThresholdMs", Long.MAX_VALUE);

        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        readyProfileQueue.enqueue(AutoParticipantProfileType.MARKET_MAKER, now.minusSeconds(1));
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
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
        ));
        when(simulationMarketSessionService.sessionAt(now))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketDailyRegimeService.applyDailyRegimes(any(), eq(now.toLocalDate()), eq(now)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), AutoParticipantProfileType.MARKET_MAKER, now, 100))
                .thenReturn(List.of());
        when(scheduleService.findNextProfileSchedules(List.of(AutoParticipantProfileType.MARKET_MAKER), now.plusSeconds(1)))
                .thenReturn(List.of(new AutoMarketReadyProfileQueue.ReadyProfile(
                        AutoParticipantProfileType.MARKET_MAKER,
                        now.plusSeconds(1)
                )));

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        assertThat(readyProfileQueue.readyAtByProfile)
                .containsEntry(AutoParticipantProfileType.MARKET_MAKER, now.plusSeconds(1));
        verify(autoParticipantOrderService, never()).placeAutoOrders(any(), any(), any(), anyDouble(), any(LocalDateTime.class));
    }

    @Test
    void runAutoMarketStep_missingSchedulesDoesNotBootstrapInsideOrderWorker() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileLock autoMarketProfileLock = profileType -> Optional.of(() -> {
        });
        Executor directExecutor = Runnable::run;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                autoMarketProfileLock,
                readyProfileQueue(AutoParticipantProfileType.NOISE_TRADER),
                directExecutor,
                new AutoMarketGenerationSlotLimiter(12),
                meterRegistry
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
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        ProfilePolicy noisePolicy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = Map.of(AutoParticipantProfileType.NOISE_TRADER, noisePolicy);
        when(simulationClockService.currentSnapshot()).thenReturn(clock);
        when(simulationMarketSessionService.sessionAt(clock.simulationDateTime()))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketDailyRegimeService.applyDailyRegimes(any(), eq(now.toLocalDate()), eq(now)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(profilePolicies);
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), AutoParticipantProfileType.NOISE_TRADER, now, 100))
                .thenReturn(List.of());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(scheduleService, never()).ensureSchedules(any(), any(), any());
        verify(scheduleService, never()).claimDueStrategies(any(), any(), any(), eq(true));
        verify(autoParticipantOrderService, never()).placeAutoOrders(any(), any(), any(), anyDouble(), any(LocalDateTime.class));
    }

    @Test
    void runAutoMarketStep_orderGenerationDeadlockRetriesChunkTransaction() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileLock autoMarketProfileLock = profileType -> Optional.of(() -> {
        });
        Executor directExecutor = Runnable::run;
        SimpleMeterRegistry deadlockMeterRegistry = new SimpleMeterRegistry();
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                autoMarketProfileLock,
                readyProfileQueue(AutoParticipantProfileType.NOISE_TRADER),
                directExecutor,
                new AutoMarketGenerationSlotLimiter(12),
                deadlockMeterRegistry
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
        when(simulationMarketSessionService.sessionAt(clock.simulationDateTime()))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of(config));
        when(autoMarketDailyRegimeService.applyDailyRegimes(any(), eq(now.toLocalDate()), eq(now)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(profileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(profilePolicies);
        when(profileBehaviorSupport.policy(profilePolicies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(noisePolicy);
        when(autoMarketReader.findDueParticipantSymbolStrategies(List.of(config), AutoParticipantProfileType.NOISE_TRADER, now, 100))
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
        when(autoParticipantOrderService.placeAutoOrders(List.of(strategy), config, profilePolicies, 0.0, now))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(new AutoParticipantOrderGenerationResult(
                        3,
                        2,
                        1,
                        1,
                        0,
                        Map.of(
                                AutoMarketOrderDropReason.SIDE_NOT_SELECTED, 1,
                                AutoMarketOrderDropReason.BUY_RESERVATION_FAILED, 1
                        )
                ));

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isEqualTo(1);
        assertThat(deadlockMeterRegistry.counter("stock.auto.market.order.deadlock.retries").count()).isEqualTo(1.0);
        assertThat(deadlockMeterRegistry.counter("stock.auto.market.order.decisions").count()).isEqualTo(3.0);
        assertThat(deadlockMeterRegistry.counter("stock.auto.market.order.planned").count()).isEqualTo(2.0);
        assertThat(deadlockMeterRegistry.counter("stock.auto.market.order.stored").count()).isEqualTo(1.0);
        assertThat(deadlockMeterRegistry.counter(
                "stock.auto.market.order.dropped",
                "reason",
                AutoMarketOrderDropReason.SIDE_NOT_SELECTED.metricTag()
        ).count()).isEqualTo(1.0);
        assertThat(deadlockMeterRegistry.counter(
                "stock.auto.market.order.dropped",
                "reason",
                AutoMarketOrderDropReason.BUY_RESERVATION_FAILED.metricTag()
        ).count()).isEqualTo(1.0);
        verify(autoParticipantOrderService, times(2)).placeAutoOrders(List.of(strategy), config, profilePolicies, 0.0, now);
        verify(scheduleService, times(1)).completeStrategies(List.of(strategy), profilePolicies, now);
    }

    @Test
    void runAutoMarketStep_pausedSimulationClockSkipsGenerationEvenDuringRegularSession() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService autoMarketDailyRegimeService = mock(AutoMarketDailyRegimeService.class);
        AutoParticipantOrderService autoParticipantOrderService = mock(AutoParticipantOrderService.class);
        AutoParticipantOrderScheduleService scheduleService = mock(AutoParticipantOrderScheduleService.class);
        AutoProfileBehaviorSupport profileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AutoMarketProfileLock autoMarketProfileLock = profileType -> Optional.of(() -> {
        });
        AutoMarketService service = new AutoMarketService(
                autoMarketReader,
                autoMarketDailyRegimeService,
                autoParticipantOrderService,
                scheduleService,
                profileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                autoMarketProfileLock,
                readyProfileQueue(),
                Runnable::run,
                new AutoMarketGenerationSlotLimiter(12),
                new SimpleMeterRegistry()
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
        when(simulationMarketSessionService.sessionAt(clock.simulationDateTime()))
                .thenReturn(SimulationMarketSession.REGULAR);

        int processedCount = service.runAutoMarketStep();

        assertThat(processedCount).isZero();
        verify(autoMarketReader, never()).findEnabledConfigs();
        verify(transactionTemplate, never()).execute(any());
    }

    private AutoMarketReadyProfileQueue readyProfileQueue(AutoParticipantProfileType... profileTypes) {
        Queue<AutoParticipantProfileType> readyProfiles = new ArrayDeque<>(List.of(profileTypes));
        return new AutoMarketReadyProfileQueue() {
            @Override
            public boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
                return true;
            }

            @Override
            public Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now) {
                return Optional.ofNullable(readyProfiles.poll());
            }

            @Override
            public Map<AutoParticipantProfileType, LocalDateTime> snapshot() {
                return Map.of();
            }
        };
    }

    private static final class InMemoryReadyProfileQueue implements AutoMarketReadyProfileQueue {

        private final Map<AutoParticipantProfileType, LocalDateTime> readyAtByProfile = new LinkedHashMap<>();

        @Override
        public boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
            readyAtByProfile.put(profileType, readyAt);
            return true;
        }

        @Override
        public Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now) {
            Optional<Map.Entry<AutoParticipantProfileType, LocalDateTime>> claimedEntry = readyAtByProfile.entrySet()
                    .stream()
                    .filter(entry -> !entry.getValue().isAfter(now))
                    .min(Comparator.comparing(Map.Entry::getValue));
            claimedEntry.ifPresent(entry -> readyAtByProfile.remove(entry.getKey()));
            return claimedEntry.map(Map.Entry::getKey);
        }

        @Override
        public Map<AutoParticipantProfileType, LocalDateTime> snapshot() {
            return Map.copyOf(readyAtByProfile);
        }
    }
}
