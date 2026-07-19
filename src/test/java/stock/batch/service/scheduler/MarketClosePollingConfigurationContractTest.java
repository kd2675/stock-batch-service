package stock.batch.service.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketClosePollingConfigurationContractTest {

    @Test
    void marketClosePollingDefault_matchesSchedulerYamlAndDocumentation() throws IOException {
        String schedulerSource = Files.readString(
                Path.of("src/main/java/stock/batch/service/scheduler/PortfolioSettlementScheduler.java"),
                StandardCharsets.UTF_8
        );
        String applicationYaml = Files.readString(
                Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8
        );
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertThat(schedulerSource).contains("${stock.batch.market-close.poll-fixed-delay-ms:10000}");
        assertThat(applicationYaml).contains("poll-fixed-delay-ms: ${STOCK_BATCH_MARKET_CLOSE_POLL_FIXED_DELAY_MS:10000}");
        assertThat(readme).contains("기본값은 10000ms");
    }
}
