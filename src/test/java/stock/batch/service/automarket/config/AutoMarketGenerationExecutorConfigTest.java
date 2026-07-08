package stock.batch.service.automarket.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

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
}
