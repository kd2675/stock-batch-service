package stock.batch.service.automarket.v3;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AutoParticipantBehaviorKernelTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 18);
    private static final LocalDateTime OPEN = TRADE_DATE.atTime(9, 0);
    private static final LocalDateTime CLOSE = TRADE_DATE.atTime(15, 30);
    private static final AutoParticipantV3Policy POLICY = AutoParticipantV3Policy.defaults(3L);

    private final AutoParticipantBehaviorKernel kernel = new AutoParticipantBehaviorKernel();

    @Test
    void sampleDailyState_sameSeedDateAndPolicy_replaysExactly() {
        AutoParticipantActivityState first = kernel.sampleDailyState(
                AutoParticipantActivityState.NORMAL,
                7,
                91_337L,
                TRADE_DATE,
                POLICY
        );
        AutoParticipantActivityState replay = kernel.sampleDailyState(
                AutoParticipantActivityState.NORMAL,
                7,
                91_337L,
                TRADE_DATE,
                POLICY
        );

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void nextAttentionAt_offlineState_hasNoVoluntaryClock() {
        LocalDateTime next = kernel.nextAttentionAt(
                OPEN,
                OPEN,
                CLOSE,
                AutoParticipantActivityState.OFFLINE,
                10,
                42L,
                1L,
                POLICY
        );

        assertThat(next).isNull();
    }

    @Test
    void nextAttentionAt_exactSchedule_isIndependentOfDispatcherPollingInterval() {
        List<LocalDateTime> oneSecond = dueSchedule(1);
        List<LocalDateTime> tenSeconds = dueSchedule(10);

        assertThat(tenSeconds).containsExactlyElementsOf(oneSecond);
    }

    @Test
    void selectVoluntarySymbol_excludesDisabledSymbolEvenWhenItsScoreDominates() {
        String selected = kernel.selectVoluntarySymbol(
                List.of(
                        new AutoParticipantBehaviorKernel.SymbolCandidate("DISABLED", false, 100, 100, 100, 0),
                        new AutoParticipantBehaviorKernel.SymbolCandidate("ENABLED", true, 0.4, 0.2, 0.1, 0.3)
                ),
                42L,
                TRADE_DATE,
                1L,
                POLICY
        );

        assertThat(selected).isEqualTo("ENABLED");
    }

    @Test
    void shouldExecuteVoluntaryOrder_sameEventSequence_replaysExactly() {
        boolean first = kernel.shouldExecuteVoluntaryOrder(
                0.7,
                0.1,
                0.4,
                0.9,
                AutoParticipantActivityState.NORMAL,
                42L,
                TRADE_DATE,
                7L,
                POLICY
        );
        boolean replay = kernel.shouldExecuteVoluntaryOrder(
                0.7,
                0.1,
                0.4,
                0.9,
                AutoParticipantActivityState.NORMAL,
                42L,
                TRADE_DATE,
                7L,
                POLICY
        );

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void reentryFactor_recoversSmoothlyWithoutHardBlocking() {
        double immediate = kernel.reentryFactor(0L, POLICY);
        double later = kernel.reentryFactor(600L, POLICY);

        assertThat(later).isGreaterThan(immediate).isLessThan(1.0);
    }

    private List<LocalDateTime> dueSchedule(int dispatcherSeconds) {
        List<LocalDateTime> schedule = new ArrayList<>();
        LocalDateTime cursor = OPEN;
        for (long eventSequence = 1L; eventSequence <= 8L; eventSequence++) {
            LocalDateTime next = kernel.nextAttentionAt(
                    cursor,
                    OPEN,
                    CLOSE,
                    AutoParticipantActivityState.HIGH,
                    8,
                    42L,
                    eventSequence,
                    POLICY
            );
            if (next == null) {
                break;
            }
            LocalDateTime observedDue = OPEN.plusSeconds(
                    roundUpSeconds(java.time.Duration.between(OPEN, next).toSeconds(), dispatcherSeconds)
            );
            assertThat(observedDue).isAfterOrEqualTo(next);
            schedule.add(next);
            cursor = next;
        }
        return schedule;
    }

    private long roundUpSeconds(long seconds, int interval) {
        return Math.floorDiv(seconds + interval - 1L, interval) * interval;
    }
}
