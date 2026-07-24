package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.automarket.profile.NewsReactiveBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

class AutoMarketServiceProfileSelectionTest {

    private final ProfilePolicy newsPolicy = new NewsReactiveBehavior().defaultPolicy();

    @Test
    void profileSelectionSignal_v2NewsSignal_usesEqualActivityForOppositeDirections() {
        AutoParticipantStrategy strategy = strategy(AutoParticipantBehaviorModelVersion.V2);

        double bullish = AutoMarketService.profileSelectionSignal(strategy, newsPolicy, 0.0, 0.8);
        double bearish = AutoMarketService.profileSelectionSignal(strategy, newsPolicy, 0.0, -0.8);

        assertThat(bearish).isEqualTo(bullish);
    }

    private AutoParticipantStrategy strategy(AutoParticipantBehaviorModelVersion modelVersion) {
        return new AutoParticipantStrategy(
                "auto-news",
                1L,
                5,
                AutoParticipantProfileType.NEWS_REACTIVE,
                null,
                null,
                null,
                modelVersion,
                17L,
                LocalDateTime.of(2027, 1, 18, 10, 0)
        );
    }
}
