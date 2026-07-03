package stock.batch.service.execution.lock;

import java.util.Optional;

public interface OrderBookSymbolLock {

    Optional<LockHandle> tryLock(String symbol);

    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}
