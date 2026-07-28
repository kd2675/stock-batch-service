package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;

class AutoMarketOrderExpiryServiceTest {

    private AutoMarketOrderReader reader;
    private AutoMarketOrderExecutor executor;
    private AutoProfileBehaviorSupport behaviorSupport;
    private AutoMarketOrderExpiryService service;
    private AutoMarketConfig config;
    private Map<AutoParticipantProfileType, ProfilePolicy> policies;

    @BeforeEach
    void setUp() {
        reader = mock(AutoMarketOrderReader.class);
        executor = mock(AutoMarketOrderExecutor.class);
        behaviorSupport = new AutoProfileBehaviorSupport();
        service = new AutoMarketOrderExpiryService(reader, executor, behaviorSupport);
        ReflectionTestUtils.setField(service, "expiryChunkLimit", 100);
        service.validateVolumeConfiguration();
        config = new AutoMarketConfig(
                "STOCK001", 100, 60, 1_000_000L,
                new BigDecimal("100.00"), new BigDecimal("70000.00"),
                new BigDecimal("70000.00"), null
        );
        policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                behaviorSupport.defaultPolicy(AutoParticipantProfileType.NOISE_TRADER)
        );
        when(executor.loadOrderBookState("STOCK001")).thenReturn(new AutoMarketOrderBookState(
                new BigDecimal("70000.00"),
                new BigDecimal("70100.00"),
                100L,
                100L
        ));
    }

    @Test
    void expireOldAutoOrders_pinnedExpiryIgnoresCurrentProfilePolicy() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);
        AutoOrder pinned = order(1L, AutoParticipantProfileType.PASSIVE_LIMIT_TRADER,
                AutoParticipantBehaviorModelVersion.V3, now, now.minusHours(1));
        when(reader.findExpiredAutoOrders(eq(config), any(), eq(now), anyInt()))
                .thenReturn(List.of(pinned));
        when(executor.expireOrders(any(), eq(now), eq("TTL_EXPIRED"))).thenReturn(1);

        AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                service.planExpiryCandidates(config, policies, now);
        int expired = service.expirePlannedOrders(config, plan, now);

        assertThat(expired).isEqualTo(1);
        verify(executor).expireOrders(List.of(pinned), now, "TTL_EXPIRED");
    }

    @Test
    void expireOldAutoOrders_expiresAllDueV3OrdersWithoutQuoteRoleSpecialCase() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);
        List<AutoOrder> candidates = new ArrayList<>();
        for (long id = 1; id <= 15; id++) {
            candidates.add(order(id, AutoParticipantProfileType.PASSIVE_LIMIT_TRADER,
                    AutoParticipantBehaviorModelVersion.V3, now.minusSeconds(1), now.minusHours(1), "BUY"));
        }
        for (long id = 16; id <= 30; id++) {
            candidates.add(order(id, AutoParticipantProfileType.PASSIVE_LIMIT_TRADER,
                    AutoParticipantBehaviorModelVersion.V3, now.minusSeconds(1), now.minusHours(1), "SELL"));
        }
        AutoOrder scalper = order(100L, AutoParticipantProfileType.SCALPER,
                AutoParticipantBehaviorModelVersion.V3, now.minusSeconds(1), now.minusHours(1));
        candidates.add(scalper);
        when(reader.findExpiredAutoOrders(eq(config), any(), eq(now), anyInt())).thenReturn(candidates);
        when(executor.expireOrders(any(), eq(now), eq("TTL_EXPIRED")))
                .thenAnswer(invocation -> invocation.<List<AutoOrder>>getArgument(0).size());
        ArgumentCaptor<List<AutoOrder>> expiredCaptor = ArgumentCaptor.forClass(List.class);

        AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                service.planExpiryCandidates(config, policies, now);
        int expired = service.expirePlannedOrders(config, plan, now);

        verify(executor).expireOrders(expiredCaptor.capture(), eq(now), eq("TTL_EXPIRED"));
        long passiveLimitTraderCount = expiredCaptor.getValue().stream()
                .filter(order -> order.profileType() == AutoParticipantProfileType.PASSIVE_LIMIT_TRADER)
                .count();
        boolean containsScalper = expiredCaptor.getValue().contains(scalper);
        assertThat(expired + ":" + passiveLimitTraderCount + ":" + containsScalper).isEqualTo("31:30:true");
    }

    @Test
    void expireOldAutoOrders_unpinnedOrderStillUsesProfileThreshold() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);
        AutoOrder stillFresh = order(1L, AutoParticipantProfileType.NOISE_TRADER,
                null, null, now.minusSeconds(30));
        when(reader.findExpiredAutoOrders(eq(config), any(), eq(now), anyInt()))
                .thenReturn(List.of(stillFresh));
        when(executor.expireOrders(any(), eq(now), eq("TTL_EXPIRED"))).thenReturn(0);

        AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                service.planExpiryCandidates(config, policies, now);
        int expired = service.expirePlannedOrders(config, plan, now);

        assertThat(expired).isZero();
        verify(executor).expireOrders(List.of(), now, "TTL_EXPIRED");
    }

    @Test
    void expireInstitutionOrders_usesPinnedTtlWithoutAutoParticipantJoin() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);
        AutoOrder institutionOrder = new AutoOrder(
                77L,
                700L,
                "STOCK001",
                "BUY",
                1_000L,
                250L,
                new BigDecimal("52500000.00"),
                new BigDecimal("70000.00"),
                null,
                null,
                now.minusSeconds(1),
                now.minusMinutes(11)
        );
        when(reader.findExpiredInstitutionOrders("STOCK001", now, 100))
                .thenReturn(List.of(institutionOrder));
        when(executor.expireOrders(any(), eq(now), eq("TTL_EXPIRED"))).thenReturn(1);

        AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                service.planExpiryCandidates(config, policies, now);
        int expired = service.expirePlannedOrders(config, plan, now);

        assertThat(expired).isEqualTo(1);
        verify(executor).expireOrders(List.of(institutionOrder), now, "TTL_EXPIRED");
    }

    @Test
    void expireOldAutoOrders_withoutCandidates_hasNoWork() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);
        AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                service.planExpiryCandidates(config, policies, now);

        assertThat(plan.hasWork()).isFalse();
        verify(executor, never()).expireOrders(any(), any());
    }

    @Test
    void planExpiryCandidates_withoutOpenOrders_hasNoLockWork() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 10, 0);

        AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                service.planExpiryCandidates(config, policies, now);

        assertThat(plan.hasWork()).isFalse();
    }

    private AutoOrder order(
            long id,
            AutoParticipantProfileType profileType,
            AutoParticipantBehaviorModelVersion modelVersion,
            LocalDateTime expiresAt,
            LocalDateTime createdAt
    ) {
        return order(id, profileType, modelVersion, expiresAt, createdAt, "BUY");
    }

    private AutoOrder order(
            long id,
            AutoParticipantProfileType profileType,
            AutoParticipantBehaviorModelVersion modelVersion,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            String side
    ) {
        return order(id, profileType, modelVersion, expiresAt, createdAt, side, new BigDecimal("70000.00"));
    }

    private AutoOrder order(
            long id,
            AutoParticipantProfileType profileType,
            AutoParticipantBehaviorModelVersion modelVersion,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            String side,
            BigDecimal limitPrice
    ) {
        return new AutoOrder(
                id, id, "STOCK001", side, 10L, 0L, new BigDecimal("700000.00"),
                limitPrice, profileType, modelVersion, expiresAt, createdAt
        );
    }
}
