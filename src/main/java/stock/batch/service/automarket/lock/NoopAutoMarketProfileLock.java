package stock.batch.service.automarket.lock;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-lock.type", havingValue = "none")
public class NoopAutoMarketProfileLock implements AutoMarketProfileLock {

    private static final LockHandle HANDLE = () -> {
    };

    @Override
    public Optional<LockHandle> tryLock(AutoParticipantProfileType profileType) {
        return Optional.of(HANDLE);
    }
}
