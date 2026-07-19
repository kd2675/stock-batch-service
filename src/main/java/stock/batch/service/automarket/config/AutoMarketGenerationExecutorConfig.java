package stock.batch.service.automarket.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AutoMarketGenerationExecutorConfig {

    public static final String AUTO_MARKET_GENERATION_TASK_EXECUTOR = "autoMarketGenerationTaskExecutor";
    static final int MAX_GENERATION_POOL_SIZE = 16;

    @Bean(name = AUTO_MARKET_GENERATION_TASK_EXECUTOR)
    public Executor autoMarketGenerationTaskExecutor(
            @Value("${stock.batch.auto-market.thread-pool.core-size:12}") int coreSize,
            @Value("${stock.batch.auto-market.thread-pool.max-size:12}") int maxSize,
            @Value("${stock.batch.auto-market.thread-pool.queue-capacity:0}") int queueCapacity
    ) {
        if (coreSize <= 0 || coreSize > MAX_GENERATION_POOL_SIZE) {
            throw new IllegalArgumentException(
                    "stock.batch.auto-market.thread-pool.core-size must be between 1 and %d"
                            .formatted(MAX_GENERATION_POOL_SIZE)
            );
        }
        if (maxSize < coreSize || maxSize > MAX_GENERATION_POOL_SIZE) {
            throw new IllegalArgumentException(
                    "stock.batch.auto-market.thread-pool.max-size must be between core-size and %d"
                            .formatted(MAX_GENERATION_POOL_SIZE)
            );
        }
        if (queueCapacity != 0) {
            throw new IllegalArgumentException(
                    "stock.batch.auto-market.thread-pool.queue-capacity must be 0 to reject stale generation work"
            );
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("stock-auto-market-gen-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
