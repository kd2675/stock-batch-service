package stock.batch.service.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class StockBatchSchedulerConfig {

    @Bean(name = StockBatchSchedulerNames.EXECUTION)
    public ThreadPoolTaskScheduler stockBatchExecutionTaskScheduler(
            @Value("${stock.batch.scheduler-pools.execution.pool-size:2}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        return taskScheduler("stock-batch-execution-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.AUTO_MARKET)
    public ThreadPoolTaskScheduler stockBatchAutoMarketTaskScheduler(
            @Value("${stock.batch.scheduler-pools.auto-market.pool-size:1}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        return taskScheduler("stock-batch-auto-market-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.MAINTENANCE)
    public ThreadPoolTaskScheduler stockBatchMaintenanceTaskScheduler(
            @Value("${stock.batch.scheduler-pools.maintenance.pool-size:2}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        return taskScheduler("stock-batch-maintenance-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.SIMULATION_CLOCK)
    public ThreadPoolTaskScheduler stockBatchSimulationClockTaskScheduler(
            @Value("${stock.batch.scheduler-pools.simulation-clock.pool-size:1}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        return taskScheduler("stock-batch-simulation-clock-", poolSize, shutdownAwaitSeconds);
    }

    private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix, int poolSize, int shutdownAwaitSeconds) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException(threadNamePrefix + "poolSize must be positive");
        }
        if (shutdownAwaitSeconds < 0) {
            throw new IllegalArgumentException(threadNamePrefix + "shutdownAwaitSeconds must not be negative");
        }
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        return scheduler;
    }
}
