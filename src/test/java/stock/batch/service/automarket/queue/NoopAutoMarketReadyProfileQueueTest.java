package stock.batch.service.automarket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

class NoopAutoMarketReadyProfileQueueTest {

    @Test
    void replaceAll_withoutProfiles_acceptsDisabledAutoMarket() {
        NoopAutoMarketReadyProfileQueue queue = new NoopAutoMarketReadyProfileQueue();

        assertThat(queue.replaceAll(List.of())).isZero();
    }

    @Test
    void replaceAll_withRequiredProfile_rejectsMissingQueueImplementation() {
        NoopAutoMarketReadyProfileQueue queue = new NoopAutoMarketReadyProfileQueue();

        assertThatThrownBy(() -> queue.replaceAll(List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(
                        AutoParticipantProfileType.MARKET_MAKER,
                        LocalDateTime.of(2026, 7, 22, 5, 30)
                )
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile-queue.type=none");
    }

    @Test
    void snapshot_disabledQueue_isEmpty() {
        assertThat(new NoopAutoMarketReadyProfileQueue().snapshot()).isEmpty();
    }
}
