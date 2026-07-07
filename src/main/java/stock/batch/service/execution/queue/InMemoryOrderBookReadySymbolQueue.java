package stock.batch.service.execution.queue;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "stock.batch.execution.ready-symbol-queue.type", havingValue = "memory")
public class InMemoryOrderBookReadySymbolQueue implements OrderBookReadySymbolQueue {

    private final Set<String> symbols = new LinkedHashSet<>();

    @Override
    public synchronized boolean enqueue(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return symbols.add(symbol);
    }

    @Override
    public synchronized Optional<String> poll() {
        if (symbols.isEmpty()) {
            return Optional.empty();
        }
        String symbol = symbols.iterator().next();
        symbols.remove(symbol);
        return Optional.of(symbol);
    }
}
