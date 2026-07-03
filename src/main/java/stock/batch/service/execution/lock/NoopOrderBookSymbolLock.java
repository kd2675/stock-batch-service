package stock.batch.service.execution.lock;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "stock.batch.execution.symbol-lock.type", havingValue = "none")
public class NoopOrderBookSymbolLock implements OrderBookSymbolLock {

    private static final LockHandle HANDLE = () -> {
    };

    @Override
    public Optional<LockHandle> tryLock(String symbol) {
        return Optional.of(HANDLE);
    }
}
