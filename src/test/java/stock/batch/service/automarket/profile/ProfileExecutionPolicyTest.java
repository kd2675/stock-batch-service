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
    void defaults_useDirectionalModesWithoutLiquidityProviderResponsibilities() {
        ProfileExecutionPolicy passiveLimitTrader = new PassiveLimitTraderBehavior().defaultPolicy().executionPolicy();
        ProfileExecutionPolicy profitLocker = new ProfitLockerBehavior().defaultPolicy().executionPolicy();
        ProfileExecutionPolicy longTermHolder = new LongTermHolderBehavior().defaultPolicy().executionPolicy();
        ProfileExecutionPolicy noiseTrader = new NoiseTraderBehavior().defaultPolicy().executionPolicy();

        assertThat(passiveLimitTrader.pricingMode()).isEqualTo(ProfilePricingMode.DIRECTIONAL);
        assertThat(passiveLimitTrader.inventoryMode()).isEqualTo(ProfileInventoryMode.SIGNAL_DRIVEN);
        assertThat(profitLocker.exitMode()).isEqualTo(ProfileExitMode.TAKE_PROFIT_FIRST);
        assertThat(longTermHolder.exitMode()).isEqualTo(ProfileExitMode.HOLD_LOSSES);
        assertThat(noiseTrader).isEqualTo(ProfileExecutionPolicy.defaults(
                AutoParticipantProfileType.NOISE_TRADER,
                1.0,
                1.0
        ));
    }

    @Test
    void behaviorSeedVersion_ignoresRetiredOrderCountKnobButTracksActiveExecutionMode() {
        ProfilePolicy base = new NoiseTraderBehavior().defaultPolicy();
        ProfilePolicy same = new NoiseTraderBehavior().defaultPolicy();
        ProfilePolicy retiredKnobChanged = base.withExecutionPolicy(new ProfileExecutionPolicy(
                base.executionPolicy().decisionFrequencyMultiplier(),
                base.executionPolicy().ordersPerDecisionMultiplier() + 0.25,
                base.executionPolicy().pricingMode(),
                base.executionPolicy().exitMode(),
                base.executionPolicy().inventoryMode()
        ));
        ProfilePolicy activeModeChanged = base.withExecutionPolicy(new ProfileExecutionPolicy(
                1.0,
                1.0,
                base.executionPolicy().pricingMode(),
                ProfileExitMode.TAKE_PROFIT_FIRST,
                base.executionPolicy().inventoryMode()
        ));

        assertThat(same.behaviorSeedVersion()).isEqualTo(base.behaviorSeedVersion());
        assertThat(retiredKnobChanged.behaviorSeedVersion()).isEqualTo(base.behaviorSeedVersion());
        assertThat(activeModeChanged.behaviorSeedVersion()).isNotEqualTo(base.behaviorSeedVersion());
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
                .ordersPerDecisionMultiplier()).isEqualTo(1.0);
        assertThatThrownBy(() -> inFlightSnapshot.put(
                AutoParticipantProfileType.NOISE_TRADER,
                nextRunSnapshot.get(AutoParticipantProfileType.NOISE_TRADER)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void overrideWith_missingModes_keepsV3DirectionalModesWhenWeightsCrossThresholds() {
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
