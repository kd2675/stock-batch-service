package stock.batch.service.mysql;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

class StockMysqlVerificationGateTest {

    @Test
    void mysqlSuites_explicitTaskRequiresDocker_doNotSilentlySkip() {
        assertThat(List.of(
                dockerUnavailableDisables(StockMysqlConcurrencyTest.class),
                dockerUnavailableDisables(StockMysqlDdlMigrationTest.class)
        )).containsOnly(false);
    }

    private boolean dockerUnavailableDisables(Class<?> testClass) {
        return testClass.getAnnotation(Testcontainers.class).disabledWithoutDocker();
    }
}
