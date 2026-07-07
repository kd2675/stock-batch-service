package stock.batch.service.automarket.lock;

import java.util.Optional;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public interface AutoMarketProfileLock {

    Optional<LockHandle> tryLock(AutoParticipantProfileType profileType);

    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}
