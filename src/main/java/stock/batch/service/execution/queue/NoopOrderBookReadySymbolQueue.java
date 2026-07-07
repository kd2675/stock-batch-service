package stock.batch.service.execution.queue;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "stock.batch.execution.ready-symbol-queue.type", havingValue = "none")
public class NoopOrderBookReadySymbolQueue implements OrderBookReadySymbolQueue {

    @Override
    public boolean enqueue(String symbol) {
        return false;
    }

    @Override
    public Optional<String> poll() {
        return Optional.empty();
    }
}
