package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.AutoProfileBehaviorRegistry;
import stock.batch.service.automarket.profile.BehavioralMemory;
import stock.batch.service.automarket.profile.FundingBudgetSnapshot;
import stock.batch.service.automarket.profile.MarketSignalSnapshot;
import stock.batch.service.automarket.profile.ParticipantPortfolioSnapshot;
import stock.batch.service.automarket.profile.ProfileDecision;
import stock.batch.service.automarket.profile.ProfileDecisionAction;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;

class AutoParticipantOrderRiskPolicyTest {

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    private final AutoProfileBehaviorRegistry registry = AutoProfileBehaviorRegistry.createDefault();
    private final AutoParticipantOrderService service = new AutoParticipantOrderService(
            mock(AutoMarketReader.class),
            mock(AutoMarketOrderExecutor.class),
            mock(AutoParticipantOrderPricing.class),
            mock(AutoProfileBehaviorSupport.class)
    );

    @Test
    void applyProfileRiskQuantity_whale_capsOrderByVolumeAndOppositeDepth() {
        ProfileSignalContext context = context(
                AutoParticipantProfileType.WHALE,
                0L,
                0L,
                new BigDecimal("1000000.00"),
                signals(10_000L, 20_000L, 400L)
        );

        long quantity = service.applyProfileRiskQuantity(
                AutoParticipantProfileType.WHALE,
                context,
                decision(ProfileDecisionAction.BUY, ProfileDecisionReason.SIGNAL),
                "BUY",
                PRICE,
                5_000L
        );

        assertThat(quantity).isEqualTo(100L);
    }

    @Test
    void applyProfileRiskQuantity_cashDefensive_includesExistingAndOpenBuyExposure() {
        ProfileSignalContext context = context(
                AutoParticipantProfileType.CASH_DEFENSIVE,
                3_000L,
                400L,
                new BigDecimal("1000000.00"),
                MarketSignalSnapshot.EMPTY
        );

        long quantity = service.applyProfileRiskQuantity(
                AutoParticipantProfileType.CASH_DEFENSIVE,
                context,
                decision(ProfileDecisionAction.BUY, ProfileDecisionReason.PORTFOLIO_TARGET),
                "BUY",
                PRICE,
                1_000L
        );

        assertThat(quantity).isEqualTo(100L);
    }

    @Test
    void applyProfileRiskQuantity_exitOrder_isNotReducedByNormalPerDecisionCapitalCap() {
        ProfileSignalContext context = context(
                AutoParticipantProfileType.STOP_LOSS_TRADER,
                1_000L,
                0L,
                new BigDecimal("1000000.00"),
                MarketSignalSnapshot.EMPTY
        );

        long quantity = service.applyProfileRiskQuantity(
                AutoParticipantProfileType.STOP_LOSS_TRADER,
                context,
                decision(ProfileDecisionAction.SELL, ProfileDecisionReason.EXIT_THRESHOLD),
                "SELL",
                PRICE,
                900L
        );

        assertThat(quantity).isEqualTo(900L);
    }

    @Test
    void adjustQuantityForOrderPressure_strongCrowdSignalKeepsHalfButGenericSignalKeepsQuarter() {
        long crowdQuantity = service.adjustQuantityForOrderPressure(
                "BUY",
                1_000L,
                0.90,
                ProfileDecisionReason.SIGNAL,
                0.80,
                AutoParticipantProfileType.FOMO_BUYER
        );
        long genericQuantity = service.adjustQuantityForOrderPressure(
                "BUY",
                1_000L,
                0.90,
                ProfileDecisionReason.SIGNAL,
                0.80,
                AutoParticipantProfileType.NOISE_TRADER
        );

        assertThat(crowdQuantity).isEqualTo(500L);
        assertThat(genericQuantity).isEqualTo(250L);
    }

    private ProfileSignalContext context(
            AutoParticipantProfileType profileType,
            long holdingQuantity,
            long openBuyQuantity,
            BigDecimal liquidAsset,
            MarketSignalSnapshot signals
    ) {
        AutoProfileBehavior behavior = registry.behavior(profileType);
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5_000,
                60,
                1_000_000L,
                BigDecimal.ONE,
                PRICE,
                PRICE,
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-" + profileType.name().toLowerCase(java.util.Locale.ROOT),
                profileType.ordinal() + 1L,
                5,
                profileType,
                null,
                null,
                null,
                AutoParticipantBehaviorModelVersion.V2,
                100L + profileType.ordinal(),
                LocalDateTime.of(2027, 1, 18, 12, 0)
        );
        BigDecimal holdingValue = PRICE.multiply(BigDecimal.valueOf(holdingQuantity));
        return new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                signals.momentum1Hour(),
                signals.depthImbalance(),
                0.0,
                holdingQuantity,
                liquidAsset.subtract(holdingValue).max(BigDecimal.ZERO),
                BigDecimal.ZERO,
                false,
                0,
                0.0,
                new ParticipantPortfolioSnapshot(
                        holdingQuantity,
                        0L,
                        BigDecimal.ZERO,
                        holdingValue,
                        liquidAsset,
                        holdingQuantity > 0 ? 1 : 0,
                        openBuyQuantity
                ),
                signals,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY
        );
    }

    private ProfileDecision decision(ProfileDecisionAction action, ProfileDecisionReason reason) {
        return new ProfileDecision(action, reason, 1, 1.0);
    }

    private MarketSignalSnapshot signals(long averageVolume5Day, long bidDepth, long askDepth) {
        return new MarketSignalSnapshot(
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                averageVolume5Day,
                averageVolume5Day,
                10_000L,
                1.0,
                bidDepth,
                askDepth,
                0.0,
                Long.MAX_VALUE
        );
    }
}
