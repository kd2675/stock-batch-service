package stock.batch.service.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class StockBatchSchedulerConfig {

    private static final int SCHEDULER_LIFECYCLE_PHASE = Integer.MAX_VALUE;

    @Bean(name = StockBatchSchedulerNames.EXECUTION)
    public ThreadPoolTaskScheduler stockBatchExecutionTaskScheduler(
            @Value("${stock.batch.scheduler-pools.execution.pool-size:2}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        if (poolSize < 1 || poolSize > 4) {
            throw new IllegalArgumentException(
                    "stock-batch-execution-poolSize must be between 1 and 4"
            );
        }
        return taskScheduler("stock-batch-execution-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.AUTO_MARKET)
    public ThreadPoolTaskScheduler stockBatchAutoMarketTaskScheduler(
            @Value("${stock.batch.scheduler-pools.auto-market.pool-size:1}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        requireSingleThread("stock-batch-auto-market", poolSize);
        return taskScheduler("stock-batch-auto-market-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.MAINTENANCE)
    public ThreadPoolTaskScheduler stockBatchMaintenanceTaskScheduler(
            @Value("${stock.batch.scheduler-pools.maintenance.pool-size:1}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        requireSingleThread("stock-batch-maintenance", poolSize);
        return taskScheduler("stock-batch-maintenance-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.POST_CLOSE)
    public ThreadPoolTaskScheduler stockBatchPostCloseTaskScheduler(
            @Value("${stock.batch.scheduler-pools.post-close.pool-size:1}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        requireSingleThread("stock-batch-post-close", poolSize);
        return taskScheduler("stock-batch-post-close-", poolSize, shutdownAwaitSeconds);
    }

    @Bean(name = StockBatchSchedulerNames.SIMULATION_CLOCK)
    public ThreadPoolTaskScheduler stockBatchSimulationClockTaskScheduler(
            @Value("${stock.batch.scheduler-pools.simulation-clock.pool-size:1}") int poolSize,
            @Value("${stock.batch.scheduler-pools.shutdown-await-seconds:120}") int shutdownAwaitSeconds
    ) {
        requireSingleThread("stock-batch-simulation-clock", poolSize);
        return taskScheduler("stock-batch-simulation-clock-", poolSize, shutdownAwaitSeconds);
    }

    private void requireSingleThread(String schedulerName, int poolSize) {
        if (poolSize != 1) {
            throw new IllegalArgumentException(
                    schedulerName + "-poolSize must be exactly 1 to prevent overlapping database work"
            );
        }
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
        // Stop pending scheduler triggers immediately. Only work that has already
        // started may consume the graceful-shutdown allowance. Leaving delayed
        // triggers enabled can make Spring wait for a future polling task while
        // the lifecycle has already paused its executor.
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        scheduler.setPhase(SCHEDULER_LIFECYCLE_PHASE);
        return scheduler;
    }
}
