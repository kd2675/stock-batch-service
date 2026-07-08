package stock.batch.service.execution.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OrderBookExecutionRunExecutorConfig {

    public static final String ORDER_BOOK_EXECUTION_RUN_TASK_EXECUTOR = "orderBookExecutionRunTaskExecutor";

    @Bean(name = ORDER_BOOK_EXECUTION_RUN_TASK_EXECUTOR)
    public Executor orderBookExecutionRunTaskExecutor(
            @Value("${stock.batch.order-book-execution.run-dispatcher.thread-pool.core-size:3}") int coreSize,
            @Value("${stock.batch.order-book-execution.run-dispatcher.thread-pool.max-size:3}") int maxSize,
            @Value("${stock.batch.order-book-execution.run-dispatcher.thread-pool.queue-capacity:0}") int queueCapacity
    ) {
        if (coreSize <= 0) {
            throw new IllegalArgumentException("stock.batch.order-book-execution.run-dispatcher.thread-pool.core-size must be positive");
        }
        if (maxSize < coreSize) {
            throw new IllegalArgumentException("stock.batch.order-book-execution.run-dispatcher.thread-pool.max-size must be greater than or equal to core-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("stock.batch.order-book-execution.run-dispatcher.thread-pool.queue-capacity must not be negative");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("stock-orderbook-run-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
