package stock.batch.service.automarket.config;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AutoMarketGenerationSlotLimiter {

    private final Semaphore permits;

    public AutoMarketGenerationSlotLimiter(
            @Value("${stock.batch.auto-market.thread-pool.max-size:12}") int maxConcurrentGenerations
    ) {
        if (maxConcurrentGenerations <= 0) {
            throw new IllegalArgumentException("stock.batch.auto-market.thread-pool.max-size must be positive");
        }
        this.permits = new Semaphore(maxConcurrentGenerations);
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    int availablePermits() {
        return permits.availablePermits();
    }
}
