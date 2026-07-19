package stock.batch.service.automarket.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoMarketGenerationExecutorConfigTest {

    @Test
    void autoMarketGenerationTaskExecutor_rejectsInsteadOfRunningOnCallerThread() {
        AutoMarketGenerationExecutorConfig config = new AutoMarketGenerationExecutorConfig();

        Executor executor = config.autoMarketGenerationTaskExecutor(12, 12, 0);

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
        taskExecutor.initialize();
        assertThat(taskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    void autoMarketGenerationTaskExecutor_poolAboveDatabaseSafeMaximum_rejectsStartup() {
        AutoMarketGenerationExecutorConfig config = new AutoMarketGenerationExecutorConfig();

        assertThatThrownBy(() -> config.autoMarketGenerationTaskExecutor(17, 17, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core-size must be between 1 and 16");
    }

    @Test
    void autoMarketGenerationTaskExecutor_positiveQueueCapacity_rejectsStaleWorkBacklog() {
        AutoMarketGenerationExecutorConfig config = new AutoMarketGenerationExecutorConfig();

        assertThatThrownBy(() -> config.autoMarketGenerationTaskExecutor(12, 12, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue-capacity must be 0");
    }

    @Test
    void autoMarketRunTaskExecutor_parallelConfiguration_rejectsOverlappingRuns() {
        AutoMarketRunExecutorConfig config = new AutoMarketRunExecutorConfig();

        assertThatThrownBy(() -> config.autoMarketRunTaskExecutor(1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-size must be exactly 1");
    }
}
