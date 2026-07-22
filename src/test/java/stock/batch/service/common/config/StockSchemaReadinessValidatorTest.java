package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StockSchemaReadinessValidatorTest {

    @Test
    void run_completeCanonicalH2Schema_passesAllEodRequirements() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_complete;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_missingEodSchema_failsClosedBeforeSchedulersStart() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_missing;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stock EOD schema is not ready")
                .hasMessageContaining("stock_market_session_fence");
    }

    @Test
    void run_legacyEntitlementStatusCheck_failsBeforeSchedulersStart() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_legacy_entitlement_check;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "alter table stock_corporate_action_entitlement drop constraint chk_stock_corporate_action_entitlement_status"
        );
        jdbcTemplate.execute(
                """
                alter table stock_corporate_action_entitlement
                  add constraint chk_stock_corporate_action_entitlement_status check (
                    status in ('ANNOUNCED', 'SUBSCRIBED', 'EXPIRED', 'PAID')
                  )
                """
        );
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chk_stock_corporate_action_entitlement_status CHECK token partially_subscribed");
    }
}
