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
                .sorted()
                .toList();

        assertThat(schedulerFiles).isNotEmpty();
        for (Path schedulerFile : schedulerFiles) {
            String source = Files.readString(schedulerFile, StandardCharsets.UTF_8);

            assertThat(source)
                    .as(schedulerFile.toString())
                    .contains("BatchJobRuntimeControl")
                    .contains("shouldRunScheduledJob")
                    .contains("JOB_NAME");
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
