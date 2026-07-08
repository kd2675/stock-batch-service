package stock.batch.service.automarket.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AutoMarketRunExecutorConfig {

    public static final String AUTO_MARKET_RUN_TASK_EXECUTOR = "autoMarketRunTaskExecutor";

    @Bean(name = AUTO_MARKET_RUN_TASK_EXECUTOR)
    public Executor autoMarketRunTaskExecutor(
            @Value("${stock.batch.auto-market.run-dispatcher.thread-pool.core-size:3}") int coreSize,
            @Value("${stock.batch.auto-market.run-dispatcher.thread-pool.max-size:3}") int maxSize,
            @Value("${stock.batch.auto-market.run-dispatcher.thread-pool.queue-capacity:0}") int queueCapacity
    ) {
        if (coreSize <= 0) {
            throw new IllegalArgumentException("stock.batch.auto-market.run-dispatcher.thread-pool.core-size must be positive");
        }
        if (maxSize < coreSize) {
            throw new IllegalArgumentException("stock.batch.auto-market.run-dispatcher.thread-pool.max-size must be greater than or equal to core-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("stock.batch.auto-market.run-dispatcher.thread-pool.queue-capacity must not be negative");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("stock-auto-market-run-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
