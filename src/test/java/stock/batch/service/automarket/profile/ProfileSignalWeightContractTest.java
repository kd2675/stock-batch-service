package stock.batch.service.automarket.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

class ProfileSignalWeightContractTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 18);
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    private final AutoProfileBehaviorRegistry registry = AutoProfileBehaviorRegistry.createDefault();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dedicatedSignalCases")
    void decide_zeroDedicatedSignalWeight_disablesThatV2Signal(
            AutoParticipantProfileType type,
            ProfileDecisionAction expectedDefaultAction,
            MarketSignalSnapshot marketSignals,
            long holdingQuantity,
            double unrealizedReturn
    ) {
        AutoProfileBehavior behavior = registry.behavior(type);
        ProfileSignalContext defaultContext = context(
                behavior,
                behavior.defaultPolicy(),
                marketSignals,
                holdingQuantity,
                unrealizedReturn
        );
        ProfilePolicy disabledPolicy = behavior.defaultPolicy().overrideWith(disabledCoreWeight(type));
        ProfileSignalContext disabledContext = context(
                behavior,
                disabledPolicy,
                marketSignals,
                holdingQuantity,
                unrealizedReturn
        );

        assertThat(List.of(
                behavior.decide(defaultContext).action(),
                behavior.decide(disabledContext).action()
        )).containsExactly(expectedDefaultAction, ProfileDecisionAction.HOLD);
    }

    private static Stream<Arguments> dedicatedSignalCases() {
        return Stream.of(
                Arguments.of(AutoParticipantProfileType.NEWS_REACTIVE, ProfileDecisionAction.BUY,
                        signals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.80, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.MOMENTUM_FOLLOWER, ProfileDecisionAction.BUY,
                        signals(0.0, 0.50, 0.05, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.0, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.CONTRARIAN, ProfileDecisionAction.BUY,
                        signals(0.0, 0.0, 0.0, -0.10, -0.12, 0.0, 10_000L, 10_000L, 0.0, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.HERD_FOLLOWER, ProfileDecisionAction.BUY,
                        signals(0.30, 0.0, 0.0, 0.0, 0.0, 0.0, 40_000L, 10_000L, 0.0, true), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.VALUE_ANCHOR, ProfileDecisionAction.BUY,
                        signals(0.0, 0.0, 0.0, 0.0, 0.0, -0.20, 10_000L, 10_000L, 0.0, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.SCALPER, ProfileDecisionAction.BUY,
                        signals(0.40, 0.0, 0.0, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.0, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.SWING_TRADER, ProfileDecisionAction.BUY,
                        signals(0.0, 0.0, 0.0, 0.05, 0.08, 0.0, 10_000L, 10_000L, 0.0, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.AVERAGE_DOWN_BUYER, ProfileDecisionAction.BUY,
                        signals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.0, false), 1_000L, -0.10),
                Arguments.of(AutoParticipantProfileType.STOP_LOSS_TRADER, ProfileDecisionAction.SELL,
                        signals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.0, false), 1_000L, -0.10),
                Arguments.of(AutoParticipantProfileType.FOMO_BUYER, ProfileDecisionAction.BUY,
                        signals(0.40, 0.50, 0.0, 0.0, 0.0, 0.0, 20_000L, 10_000L, 0.0, true), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.PANIC_SELLER, ProfileDecisionAction.SELL,
                        signals(-0.40, -0.50, 0.0, 0.0, 0.0, 0.0, 10_000L, 20_000L, 0.0, true), 1_000L, -0.02),
                Arguments.of(AutoParticipantProfileType.DIP_BUYER, ProfileDecisionAction.BUY,
                        signals(0.20, -0.50, 0.0, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.0, false), 0L, 0.0),
                Arguments.of(AutoParticipantProfileType.PROFIT_LOCKER, ProfileDecisionAction.SELL,
                        signals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10_000L, 10_000L, 0.0, false), 1_000L, 0.10)
        );
    }

    private ProfileSignalContext context(
            AutoProfileBehavior behavior,
            ProfilePolicy policy,
            MarketSignalSnapshot marketSignals,
            long holdingQuantity,
            double unrealizedReturn
    ) {
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
                "auto-" + behavior.type().name().toLowerCase(java.util.Locale.ROOT),
                behavior.type().ordinal() + 1L,
                5,
                behavior.type(),
                null,
                null,
                null,
                AutoParticipantBehaviorModelVersion.V2,
                17L + behavior.type().ordinal(),
                LocalDateTime.of(BUSINESS_DATE, LocalTime.NOON)
        );
        BigDecimal holdingValue = PRICE.multiply(BigDecimal.valueOf(holdingQuantity));
        BigDecimal cash = new BigDecimal("900000.00");
        return new ProfileSignalContext(
                strategy,
                config,
                policy,
                5,
                marketSignals.momentum1Hour(),
                marketSignals.depthImbalance(),
                unrealizedReturn,
                holdingQuantity,
                cash,
                BigDecimal.ZERO,
                false,
                0,
                0.0,
                new ParticipantPortfolioSnapshot(
                        holdingQuantity,
                        0L,
                        BigDecimal.ZERO,
                        holdingValue,
                        cash.add(holdingValue),
                        holdingQuantity > 0 ? 1 : 0,
                        0L
                ),
                marketSignals,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY
        );
    }

    private static MarketSignalSnapshot signals(
            double momentum5Minute,
            double momentum1Hour,
            double return1Day,
            double return3Day,
            double return5Day,
            double return20Day,
            long bidDepth,
            long askDepth,
            double reportPressure,
            boolean recentActivityAvailable
    ) {
        return new MarketSignalSnapshot(
                momentum5Minute,
                momentum1Hour,
                return1Day,
                return3Day,
                return5Day,
                return20Day,
                100_000L,
                100_000L,
                20_000L,
                1.0,
                bidDepth,
                askDepth,
                reportPressure,
                0L,
                recentActivityAvailable ? 10_000L : 0L,
                recentActivityAvailable ? 5 : 0,
                recentActivityAvailable
        );
    }

    private static AutoParticipantProfileConfig disabledCoreWeight(AutoParticipantProfileType type) {
        BigDecimal zero = BigDecimal.ZERO;
        return new AutoParticipantProfileConfig(
                type,
                type == AutoParticipantProfileType.NEWS_REACTIVE ? zero : null,
                type == AutoParticipantProfileType.MOMENTUM_FOLLOWER
                        || type == AutoParticipantProfileType.SCALPER
                        || type == AutoParticipantProfileType.SWING_TRADER
                        || type == AutoParticipantProfileType.FOMO_BUYER ? zero : null,
                type == AutoParticipantProfileType.CONTRARIAN
                        || type == AutoParticipantProfileType.VALUE_ANCHOR ? zero : null,
                null,
                type == AutoParticipantProfileType.HERD_FOLLOWER
                        || type == AutoParticipantProfileType.FOMO_BUYER
                        || type == AutoParticipantProfileType.PANIC_SELLER ? zero : null,
                null,
                null,
                null,
                type == AutoParticipantProfileType.STOP_LOSS_TRADER
                        || type == AutoParticipantProfileType.PANIC_SELLER ? zero : null,
                type == AutoParticipantProfileType.AVERAGE_DOWN_BUYER
                        || type == AutoParticipantProfileType.DIP_BUYER ? zero : null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                type == AutoParticipantProfileType.PROFIT_LOCKER ? zero : null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
