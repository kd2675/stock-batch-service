package stock.batch.service.automarket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

class InMemoryAutoMarketReadyProfileQueueTest {

    @Test
    void hasDueProfile_futureAndDueProfiles_reportsOnlyCurrentDueWork() {
        InMemoryAutoMarketReadyProfileQueue queue = new InMemoryAutoMarketReadyProfileQueue();
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 9, 0);
        queue.enqueue(AutoParticipantProfileType.MARKET_MAKER, now.plusSeconds(1));

        boolean beforeDue = queue.hasDueProfile(now);
        boolean whenDue = queue.hasDueProfile(now.plusSeconds(1));

        assertThat(List.of(beforeDue, whenDue)).containsExactly(false, true);
    }

    @Test
    void removeAll_existingAndMissingProfiles_removesOnlyExistingProfile() {
        InMemoryAutoMarketReadyProfileQueue queue = new InMemoryAutoMarketReadyProfileQueue();
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 9, 0);
        queue.enqueue(AutoParticipantProfileType.MARKET_MAKER, now);

        int removedCount = queue.removeAll(List.of(
                AutoParticipantProfileType.MARKET_MAKER,
                AutoParticipantProfileType.SCALPER
        ));

        assertThat(removedCount).isEqualTo(1);
    }

    @Test
    void replaceAll_staleAndDuplicateProfiles_leavesExactDistinctReplacement() {
        InMemoryAutoMarketReadyProfileQueue queue = new InMemoryAutoMarketReadyProfileQueue();
        LocalDateTime now = LocalDateTime.of(2026, 7, 22, 5, 30);
        queue.enqueue(AutoParticipantProfileType.VALUE_ANCHOR, now.minusMinutes(1));

        int storedCount = queue.replaceAll(List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.MARKET_MAKER, now),
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.MARKET_MAKER, now.plusSeconds(1)),
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, now.plusSeconds(2))
        ));

        assertThat(storedCount).isEqualTo(2);
        assertThat(queue.claimDueProfile(now)).isEmpty();
        assertThat(queue.claimDueProfile(now.plusSeconds(1))).contains(AutoParticipantProfileType.MARKET_MAKER);
        assertThat(queue.claimDueProfile(now.plusSeconds(2))).contains(AutoParticipantProfileType.SCALPER);
    }

    @Test
    void snapshot_returnsIndependentCompleteView() {
        InMemoryAutoMarketReadyProfileQueue queue = new InMemoryAutoMarketReadyProfileQueue();
        LocalDateTime readyAt = LocalDateTime.of(2026, 7, 22, 5, 30);
        queue.enqueue(AutoParticipantProfileType.MARKET_MAKER, readyAt);

        assertThat(queue.snapshot())
                .containsEntry(AutoParticipantProfileType.MARKET_MAKER, readyAt)
                .hasSize(1);
    }
}
