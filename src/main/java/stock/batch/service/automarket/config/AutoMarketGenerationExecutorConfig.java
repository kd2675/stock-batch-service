package stock.batch.service.automarket.config;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AutoMarketGenerationExecutorConfig {

    public static final String AUTO_MARKET_GENERATION_TASK_EXECUTOR = "autoMarketGenerationTaskExecutor";

    @Bean(name = AUTO_MARKET_GENERATION_TASK_EXECUTOR)
    public Executor autoMarketGenerationTaskExecutor(
            @Value("${stock.batch.auto-market.thread-pool.core-size:4}") int coreSize,
            @Value("${stock.batch.auto-market.thread-pool.max-size:4}") int maxSize,
            @Value("${stock.batch.auto-market.thread-pool.queue-capacity:100}") int queueCapacity
    ) {
        if (coreSize <= 0) {
            throw new IllegalArgumentException("stock.batch.auto-market.thread-pool.core-size must be positive");
        }
        if (maxSize < coreSize) {
            throw new IllegalArgumentException("stock.batch.auto-market.thread-pool.max-size must be greater than or equal to core-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("stock.batch.auto-market.thread-pool.queue-capacity must not be negative");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("stock-auto-market-gen-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
