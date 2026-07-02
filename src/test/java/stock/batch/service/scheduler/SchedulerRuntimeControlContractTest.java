package stock.batch.service.scheduler;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerRuntimeControlContractTest {

    @Test
    void scheduledJobs_checkDbRuntimeControlBeforeLaunchingBatchJob() throws IOException {
        List<Path> schedulerFiles = Files.walk(Path.of("src/main/java/stock/batch/service/scheduler"))
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(this::containsScheduledMethod)
                .filter(path -> !path.getFileName().toString().equals("SimulationClockScheduler.java"))
                .sorted()
                .toList();

        assertThat(schedulerFiles).isNotEmpty();
        for (Path schedulerFile : schedulerFiles) {
            String source = Files.readString(schedulerFile, StandardCharsets.UTF_8);

            assertThat(source)
                    .as(schedulerFile.toString())
                    .contains("StockBatchScheduledJobGuard")
                    .contains("JOB_NAME");
            assertThat(source.contains("runIfEnabled") || source.contains("runBatchIfEnabled"))
                    .as(schedulerFile.toString() + " should launch through StockBatchScheduledJobGuard")
                    .isTrue();
        }
    }

    @Test
    void scheduledJobs_useDedicatedTaskSchedulers() throws IOException {
        List<Path> schedulerFiles = Files.walk(Path.of("src/main/java/stock/batch/service/scheduler"))
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(this::containsScheduledMethod)
                .sorted()
                .toList();

        assertThat(schedulerFiles).isNotEmpty();
        for (Path schedulerFile : schedulerFiles) {
            String source = Files.readString(schedulerFile, StandardCharsets.UTF_8);

            assertThat(source)
                    .as(schedulerFile.toString())
                    .contains("scheduler = StockBatchSchedulerNames.");
        }
    }

    private boolean containsScheduledMethod(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains("@Scheduled");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read scheduler source: " + path, ex);
        }
    }
}
