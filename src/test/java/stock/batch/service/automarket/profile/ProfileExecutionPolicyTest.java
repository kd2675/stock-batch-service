package stock.batch.service.automarket.profile;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

class ProfileExecutionPolicyTest {

    @Test
    void legacy_separatesDecisionFrequencyFromOrdersPerDecisionWithoutChangingEffectiveCadence() {
        ProfileExecutionPolicy policy = ProfileExecutionPolicy.legacy(1.25, 0.60, 0.95, 0.00, 0.45);

        assertThat(policy.decisionFrequencyMultiplier()).isEqualTo(1.25 / 0.60);
        assertThat(policy.ordersPerDecisionMultiplier()).isEqualTo(1.25);
        assertThat(policy.pricingMode()).isEqualTo(ProfilePricingMode.MARKET_MAKING);
        assertThat(policy.inventoryMode()).isEqualTo(ProfileInventoryMode.TARGET_ALLOCATION);
    }

    @Test
    void legacy_subQuarterMultipliers_preservePreviousCadenceFloor() {
        ProfileExecutionPolicy policy = ProfileExecutionPolicy.legacy(0.10, 0.20, 0.00, 0.00, 0.00);

        assertThat(policy.decisionFrequencyMultiplier()).isEqualTo(1.0);
        assertThat(policy.ordersPerDecisionMultiplier()).isEqualTo(0.10);
    }

    @Test
    void explicitPolicy_keepsFrequencyIndependentFromOrderCountAndTtl() {
        ProfileExecutionPolicy policy = new ProfileExecutionPolicy(
                0.75,
                2.00,
                ProfilePricingMode.DIRECTIONAL,
                ProfileExitMode.SIGNAL_DRIVEN,
                ProfileInventoryMode.SIGNAL_DRIVEN
        );

        assertThat(policy.decisionFrequencyMultiplier()).isEqualTo(0.75);
        assertThat(policy.ordersPerDecisionMultiplier()).isEqualTo(2.00);
    }

    @Test
    void v2Default_usesExplicitProfileModesInsteadOfWeightThresholds() {
        ProfileExecutionPolicy marketMaker = new MarketMakerBehavior().defaultPolicy().executionPolicy();
        ProfileExecutionPolicy profitLocker = new ProfitLockerBehavior().defaultPolicy().executionPolicy();
        ProfileExecutionPolicy longTermHolder = new LongTermHolderBehavior().defaultPolicy().executionPolicy();
        ProfileExecutionPolicy noiseTrader = new NoiseTraderBehavior().defaultPolicy().executionPolicy();

        assertThat(marketMaker.pricingMode()).isEqualTo(ProfilePricingMode.MARKET_MAKING);
        assertThat(marketMaker.inventoryMode()).isEqualTo(ProfileInventoryMode.TARGET_ALLOCATION);
        assertThat(profitLocker.exitMode()).isEqualTo(ProfileExitMode.TAKE_PROFIT_FIRST);
        assertThat(longTermHolder.exitMode()).isEqualTo(ProfileExitMode.HOLD_LOSSES);
        assertThat(noiseTrader).isEqualTo(ProfileExecutionPolicy.v2Default(
                AutoParticipantProfileType.NOISE_TRADER,
                1.0,
                1.0
        ));
    }

    @Test
    void behaviorSeedVersion_sameBehaviorPolicy_isStableAndChangesWithExecutionPolicy() {
        ProfilePolicy base = new NoiseTraderBehavior().defaultPolicy();
        ProfilePolicy same = new NoiseTraderBehavior().defaultPolicy();
        ProfilePolicy changed = base.withExecutionPolicy(new ProfileExecutionPolicy(
                base.executionPolicy().decisionFrequencyMultiplier(),
                base.executionPolicy().ordersPerDecisionMultiplier() + 0.25,
                base.executionPolicy().pricingMode(),
                base.executionPolicy().exitMode(),
                base.executionPolicy().inventoryMode()
        ));

        assertThat(same.behaviorSeedVersion()).isEqualTo(base.behaviorSeedVersion());
        assertThat(changed.behaviorSeedVersion()).isNotEqualTo(base.behaviorSeedVersion());
    }

    @Test
    void behaviorSeedVersion_pricePressurePolicyChange_changesPolicyIdentity() {
        ProfilePolicy base = new NoiseTraderBehavior().defaultPolicy();
        ProfilePolicy changed = base.withPricePressureSensitivity(base.pricePressureSensitivity() + 0.1);

        assertThat(changed.behaviorSeedVersion()).isNotEqualTo(base.behaviorSeedVersion());
    }

    @Test
    void policySnapshot_subsequentConfigurationLoadCannotMutateInFlightPolicyMap() {
        AutoProfileBehaviorRegistry registry = AutoProfileBehaviorRegistry.createDefault();
        var inFlightSnapshot = registry.policiesWithOverrides(List.of());
        double originalOrderCountMultiplier = inFlightSnapshot.get(AutoParticipantProfileType.NOISE_TRADER)
                .executionPolicy()
                .ordersPerDecisionMultiplier();

        var nextRunSnapshot = registry.policiesWithOverrides(List.of(profileConfigWithOrdersPerDecision("2.0000")));

        assertThat(inFlightSnapshot.get(AutoParticipantProfileType.NOISE_TRADER)
                .executionPolicy()
                .ordersPerDecisionMultiplier()).isEqualTo(originalOrderCountMultiplier);
        assertThat(nextRunSnapshot.get(AutoParticipantProfileType.NOISE_TRADER)
                .executionPolicy()
                .ordersPerDecisionMultiplier()).isEqualTo(2.0);
        assertThatThrownBy(() -> inFlightSnapshot.put(
                AutoParticipantProfileType.NOISE_TRADER,
                nextRunSnapshot.get(AutoParticipantProfileType.NOISE_TRADER)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void overrideWith_missingV2Modes_keepsProfileModesWhenLegacyWeightsCrossThresholds() {
        ProfilePolicy base = new NoiseTraderBehavior().defaultPolicy();
        AutoParticipantProfileConfig config = new AutoParticipantProfileConfig(
                AutoParticipantProfileType.NOISE_TRADER,
                null, null, null, null, null, new BigDecimal("0.9500"), null, null, null, null,
                null, null, null, null, null, null, null, new BigDecimal("0.4000"), null, new BigDecimal("0.9500"),
                null, null, null,
                null, null, null, null
        );

        ProfilePolicy overridden = base.overrideWith(config);

        assertThat(overridden.executionPolicy().pricingMode()).isEqualTo(ProfilePricingMode.DIRECTIONAL);
        assertThat(overridden.executionPolicy().exitMode()).isEqualTo(ProfileExitMode.SIGNAL_DRIVEN);
        assertThat(overridden.executionPolicy().inventoryMode()).isEqualTo(ProfileInventoryMode.SIGNAL_DRIVEN);
        assertThat(overridden.forLegacyExecution().executionPolicy().pricingMode())
                .isEqualTo(ProfilePricingMode.MARKET_MAKING);
        assertThat(overridden.forLegacyExecution().executionPolicy().exitMode())
                .isEqualTo(ProfileExitMode.TAKE_PROFIT_FIRST);
    }

    private AutoParticipantProfileConfig profileConfigWithOrdersPerDecision(String multiplier) {
        return new AutoParticipantProfileConfig(
                AutoParticipantProfileType.NOISE_TRADER,
                null, null, null, null, null, null, null, null, null, null,
                null,
                null,
                new BigDecimal(multiplier),
                null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null
        );
    }
}
