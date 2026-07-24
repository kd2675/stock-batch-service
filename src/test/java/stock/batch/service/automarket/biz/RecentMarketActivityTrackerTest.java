package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class RecentMarketActivityTrackerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2027, 1, 18, 10, 0);

    @Test
    void snapshot_recentExecutions_aggregatesQuantityAndDistinctParticipants() {
        RecentMarketActivityTracker tracker = new RecentMarketActivityTracker();
        tracker.record("STOCK001", 100L, 1L, 2L, BASE_TIME);
        tracker.record("STOCK001", 200L, 1L, 3L, BASE_TIME.plusSeconds(40));
        observeFullWindow(tracker, BASE_TIME.plusMinutes(4).plusSeconds(30));

        RecentMarketActivityTracker.RecentMarketActivitySnapshot snapshot =
                tracker.snapshot("STOCK001", BASE_TIME.plusMinutes(4).plusSeconds(30));

        assertThat(snapshot)
                .extracting(
                        RecentMarketActivityTracker.RecentMarketActivitySnapshot::executionQuantity,
                        RecentMarketActivityTracker.RecentMarketActivitySnapshot::participantCount,
                        RecentMarketActivityTracker.RecentMarketActivitySnapshot::available
                )
                .containsExactly(300L, 3, true);
    }

    @Test
    void snapshot_fullObservedWindowWithoutRecentExecution_returnsAvailableZero() {
        RecentMarketActivityTracker tracker = new RecentMarketActivityTracker();
        tracker.record("STOCK001", 100L, 1L, 2L, BASE_TIME);
        observeFullWindow(tracker, BASE_TIME.plusMinutes(6));

        RecentMarketActivityTracker.RecentMarketActivitySnapshot snapshot =
                tracker.snapshot("STOCK001", BASE_TIME.plusMinutes(6));

        assertThat(snapshot)
                .extracting(
                        RecentMarketActivityTracker.RecentMarketActivitySnapshot::executionQuantity,
                        RecentMarketActivityTracker.RecentMarketActivitySnapshot::participantCount,
                        RecentMarketActivityTracker.RecentMarketActivitySnapshot::available
                )
                .containsExactly(0L, 0, true);
    }

    @Test
    void snapshot_elapsedWindowWithObservationGap_remainsUnavailable() {
        RecentMarketActivityTracker tracker = new RecentMarketActivityTracker();
        tracker.record("STOCK001", 100L, 1L, 2L, BASE_TIME);

        RecentMarketActivityTracker.RecentMarketActivitySnapshot snapshot =
                tracker.snapshot("STOCK001", BASE_TIME.plusMinutes(6));

        assertThat(snapshot).isEqualTo(RecentMarketActivityTracker.RecentMarketActivitySnapshot.EMPTY);
    }

    @Test
    void snapshot_beforeFullObservationWindow_returnsUnavailable() {
        RecentMarketActivityTracker tracker = new RecentMarketActivityTracker();
        tracker.observe("STOCK001", BASE_TIME);

        RecentMarketActivityTracker.RecentMarketActivitySnapshot snapshot =
                tracker.snapshot("STOCK001", BASE_TIME.plusMinutes(2));

        assertThat(snapshot).isEqualTo(RecentMarketActivityTracker.RecentMarketActivitySnapshot.EMPTY);
    }

    @Test
    void snapshot_outOfOrderCommit_keepsNewestTenBuckets() {
        RecentMarketActivityTracker tracker = new RecentMarketActivityTracker();
        tracker.record("STOCK001", 20L, 1L, 2L, BASE_TIME.plusMinutes(4));
        tracker.record("STOCK001", 10L, 3L, 4L, BASE_TIME);
        observeFullWindow(tracker, BASE_TIME.plusMinutes(4).plusSeconds(30));

        RecentMarketActivityTracker.RecentMarketActivitySnapshot snapshot =
                tracker.snapshot("STOCK001", BASE_TIME.plusMinutes(4).plusSeconds(30));

        assertThat(snapshot.executionQuantity()).isEqualTo(30L);
    }

    private void observeFullWindow(RecentMarketActivityTracker tracker, LocalDateTime asOf) {
        LocalDateTime firstSlot = asOf.minusSeconds(
                (long) (RecentMarketActivityTracker.WINDOW_BUCKET_COUNT - 1)
                        * RecentMarketActivityTracker.BUCKET_SECONDS
        );
        for (int index = 0; index < RecentMarketActivityTracker.WINDOW_BUCKET_COUNT; index++) {
            tracker.observe(
                    "STOCK001",
                    firstSlot.plusSeconds((long) index * RecentMarketActivityTracker.BUCKET_SECONDS)
            );
        }
    }
}
