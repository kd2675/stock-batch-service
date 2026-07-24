package stock.batch.service.mysql;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class StockMysqlVerificationGateTest {

    @Test
    void mysqlSuites_explicitTaskRequiresDocker_doNotSilentlySkip() {
        assertThat(List.of(
                dockerUnavailableDisables(StockMysqlConcurrencyTest.class),
                dockerUnavailableDisables(StockMysqlDdlMigrationTest.class)
        )).containsOnly(false);
    }

    @Test
    void mysqlSuites_ownContainerManagedMysql_doNotUseExternalDatabaseFallback() {
        assertThat(List.of(
                hasContainerManagedMysql(StockMysqlConcurrencyTest.class),
                hasContainerManagedMysql(StockMysqlDdlMigrationTest.class)
        )).containsOnly(true);
    }

    private boolean dockerUnavailableDisables(Class<?> testClass) {
        return testClass.getAnnotation(Testcontainers.class).disabledWithoutDocker();
    }

    private boolean hasContainerManagedMysql(Class<?> testClass) {
        return Stream.of(testClass.getDeclaredFields())
                .anyMatch(field ->
                        field.isAnnotationPresent(Container.class)
                                && MySQLContainer.class.isAssignableFrom(field.getType())
                );
    }
}
