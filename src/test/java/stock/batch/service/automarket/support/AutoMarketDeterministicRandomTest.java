package stock.batch.service.automarket.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

class AutoMarketDeterministicRandomTest {

    @Test
    void stableTrait_sameAccount_remainsConstantAcrossDecisionSlots() {
        AutoParticipantStrategy morning = strategy(17L, LocalDateTime.of(2027, 1, 18, 9, 0));
        AutoParticipantStrategy afternoon = strategy(17L, LocalDateTime.of(2027, 1, 18, 15, 0));

        double morningValue = AutoMarketDeterministicRandom.stableRange(
                morning, "V2:STOP_LOSS:RETURN", 0.04, 0.06
        );
        double afternoonValue = AutoMarketDeterministicRandom.stableRange(
                afternoon, "V2:STOP_LOSS:RETURN", 0.04, 0.06
        );

        assertThat(afternoonValue).isEqualTo(morningValue);
    }

    @Test
    void stableTrait_differentAccountSeed_producesDifferentOffsetWithinRange() {
        double first = AutoMarketDeterministicRandom.stableRange(
                strategy(17L, LocalDateTime.of(2027, 1, 18, 9, 0)),
                "V2:DAY_TRADER:LIQUIDATION_WINDOW_SECONDS",
                2_700,
                4_500
        );
        double second = AutoMarketDeterministicRandom.stableRange(
                strategy(18L, LocalDateTime.of(2027, 1, 18, 9, 0)),
                "V2:DAY_TRADER:LIQUIDATION_WINDOW_SECONDS",
                2_700,
                4_500
        );

        assertThat(first).isBetween(2_700.0, 4_500.0);
        assertThat(second).isBetween(2_700.0, 4_500.0).isNotEqualTo(first);
    }

    @Test
    void seed_sameParticipantSymbolSlotAndVersion_isStable() {
        LocalDateTime slot = LocalDateTime.of(2027, 1, 18, 9, 0);
        AutoParticipantStrategy strategy = strategy(slot);

        long first = AutoMarketDeterministicRandom.seed(strategy, "DEMO001", slot.plusSeconds(5), "V2");
        long second = AutoMarketDeterministicRandom.seed(strategy, "DEMO001", slot.plusHours(1), "V2");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void seed_differentDecisionSlot_changesDeterministicSequence() {
        LocalDateTime firstSlot = LocalDateTime.of(2027, 1, 18, 9, 0);

        long first = AutoMarketDeterministicRandom.seed(strategy(firstSlot), "DEMO001", firstSlot, "V2");
        long second = AutoMarketDeterministicRandom.seed(
                strategy(firstSlot.plusSeconds(10)),
                "DEMO001",
                firstSlot,
                "V2"
        );

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void seed_differentProfileOrPolicyVersion_changesDeterministicSequence() {
        LocalDateTime slot = LocalDateTime.of(2027, 1, 18, 9, 0);
        AutoParticipantStrategy noise = strategy(123456789L, slot);
        AutoParticipantStrategy observer = new AutoParticipantStrategy(
                noise.userKey(),
                noise.accountId(),
                noise.intensity(),
                AutoParticipantProfileType.OBSERVER,
                noise.recurringCashAmount(),
                noise.recurringCashIntervalValue(),
                noise.recurringCashIntervalUnit(),
                noise.behaviorModelVersion(),
                noise.behaviorSeed(),
                noise.decisionSlotAt()
        );

        long base = AutoMarketDeterministicRandom.seed(noise, "DEMO001", slot, "V2:policy-a");
        long changedProfile = AutoMarketDeterministicRandom.seed(observer, "DEMO001", slot, "V2:policy-a");
        long changedPolicy = AutoMarketDeterministicRandom.seed(noise, "DEMO001", slot, "V2:policy-b");

        assertThat(changedProfile).isNotEqualTo(base);
        assertThat(changedPolicy).isNotEqualTo(base);
    }

    @Test
    void withSeed_sameSeed_replaysAllRandomOperations() {
        String first = AutoMarketRandomSupport.withSeed(42L, () -> sampleRandomOperations());
        String second = AutoMarketRandomSupport.withSeed(42L, () -> sampleRandomOperations());

        assertThat(second).isEqualTo(first);
    }

    private String sampleRandomOperations() {
        return AutoMarketRandomSupport.chance(0.5)
                + ":" + AutoMarketRandomSupport.nextInt(1, 100)
                + ":" + AutoMarketRandomSupport.noise(1.0, 1.0);
    }

    private AutoParticipantStrategy strategy(LocalDateTime decisionSlot) {
        return strategy(123456789L, decisionSlot);
    }

    private AutoParticipantStrategy strategy(long behaviorSeed, LocalDateTime decisionSlot) {
        return new AutoParticipantStrategy(
                "auto-" + behaviorSeed,
                behaviorSeed,
                5,
                AutoParticipantProfileType.NOISE_TRADER,
                null,
                null,
                null,
                AutoParticipantBehaviorModelVersion.V2,
                behaviorSeed,
                decisionSlot
        );
    }
}
