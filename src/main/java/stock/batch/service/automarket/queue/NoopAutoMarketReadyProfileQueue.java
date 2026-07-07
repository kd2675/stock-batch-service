package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-queue.type", havingValue = "none")
public class NoopAutoMarketReadyProfileQueue implements AutoMarketReadyProfileQueue {

    @Override
    public boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
        return false;
    }

    @Override
    public Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now) {
        return Optional.empty();
    }
}
