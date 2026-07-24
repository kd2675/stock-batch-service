package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class AutoParticipantPositionActivityTrackerTest {

    private static final LocalDateTime OPENED_AT = LocalDateTime.of(2027, 1, 18, 10, 0);

    @Test
    void snapshot_unregisteredPosition_reportsUnavailable() {
        AutoParticipantPositionActivityTracker tracker = new AutoParticipantPositionActivityTracker();

        assertThat(tracker.snapshot(1L, "STOCK001", OPENED_AT.plusMinutes(1)))
                .isEqualTo(AutoParticipantPositionActivityTracker.PositionAgeSnapshot.UNAVAILABLE);
    }

    @Test
    void record_registeredEmptyPosition_startsClockOnCommittedBuy() {
        AutoParticipantPositionActivityTracker tracker = new AutoParticipantPositionActivityTracker();
        tracker.register(1L, "STOCK001", 0L, OPENED_AT.minusSeconds(1));

        tracker.record("STOCK001", 10L, 1L, 2L, OPENED_AT);

        assertThat(tracker.snapshot(1L, "STOCK001", OPENED_AT.plusSeconds(240)))
                .isEqualTo(new AutoParticipantPositionActivityTracker.PositionAgeSnapshot(240L, true));
    }

    @Test
    void record_partialSell_preservesOriginalPositionClock() {
        AutoParticipantPositionActivityTracker tracker = new AutoParticipantPositionActivityTracker();
        tracker.register(1L, "STOCK001", 0L, OPENED_AT.minusSeconds(1));
        tracker.record("STOCK001", 10L, 1L, 2L, OPENED_AT);

        tracker.record("STOCK001", 4L, 3L, 1L, OPENED_AT.plusSeconds(120));

        assertThat(tracker.snapshot(1L, "STOCK001", OPENED_AT.plusSeconds(240)))
                .isEqualTo(new AutoParticipantPositionActivityTracker.PositionAgeSnapshot(240L, true));
    }

    @Test
    void record_fullSell_clearsPositionClock() {
        AutoParticipantPositionActivityTracker tracker = new AutoParticipantPositionActivityTracker();
        tracker.register(1L, "STOCK001", 0L, OPENED_AT.minusSeconds(1));
        tracker.record("STOCK001", 10L, 1L, 2L, OPENED_AT);

        tracker.record("STOCK001", 10L, 3L, 1L, OPENED_AT.plusSeconds(120));

        assertThat(tracker.snapshot(1L, "STOCK001", OPENED_AT.plusSeconds(240)))
                .isEqualTo(AutoParticipantPositionActivityTracker.PositionAgeSnapshot.UNAVAILABLE);
    }

    @Test
    void register_existingHoldingAfterRestart_doesNotInventPositionAge() {
        AutoParticipantPositionActivityTracker tracker = new AutoParticipantPositionActivityTracker();

        tracker.register(1L, "STOCK001", 10L, OPENED_AT);

        assertThat(tracker.snapshot(1L, "STOCK001", OPENED_AT.plusSeconds(240)))
                .isEqualTo(AutoParticipantPositionActivityTracker.PositionAgeSnapshot.UNAVAILABLE);
    }
}
