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

    @Test
    void run_nullableProfileExecutionPolicy_failsBeforeSchedulersStart() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_nullable_profile_policy;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        new JdbcTemplate(dataSource).execute(
                "alter table stock_auto_participant_profile_config alter column pricing_mode drop not null"
        );
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_auto_participant_profile_config.pricing_mode NOT NULL constraint");
    }

    @Test
    void run_legacyPortfolioReturnContract_failsBeforeSettlementStarts() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_legacy_return_contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "alter table portfolio_snapshot drop constraint chk_portfolio_snapshot_return_contract"
        );
        jdbcTemplate.execute(
                """
                alter table portfolio_snapshot
                  add constraint chk_portfolio_snapshot_return_contract check (
                    return_rate_status in ('DEFINED', 'LEGACY_UNVERIFIED')
                  )
                """
        );
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "chk_portfolio_snapshot_return_contract CHECK token undefined_zero_contribution"
                );
    }

    @Test
    void run_partialAutoProfileCheck_failsBeforeSchedulersStart() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_partial_profile_check;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("alter table stock_order drop constraint chk_stock_order_auto_profile_type");
        jdbcTemplate.execute(
                """
                alter table stock_order
                  add constraint chk_stock_order_auto_profile_type check (
                    auto_profile_type is null or auto_profile_type in ('MARKET_MAKER', 'OBSERVER')
                  )
                """
        );
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chk_stock_order_auto_profile_type CHECK token news_reactive");
    }

    @Test
    void run_missingFundingBudgetActionIndex_failsBeforeSchedulersStart() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_funding_action_index;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        new JdbcTemplate(dataSource).execute("drop index idx_stock_auto_funding_budget_action");
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "stock_auto_participant_funding_budget.idx_stock_auto_funding_budget_action index"
                );
    }

    @Test
    void run_legacyShadowSchema_failsBeforeSchedulersStart() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_schema_readiness_legacy_shadow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                alter table stock_auto_participant
                  add column behavior_evaluation_mode varchar(20) not null default 'EXECUTE'
                """);
        jdbcTemplate.execute("""
                alter table stock_auto_participant
                  add constraint chk_stock_auto_participant_behavior_evaluation
                    check (behavior_evaluation_mode in ('EXECUTE', 'SHADOW'))
                """);
        jdbcTemplate.execute("""
                create table stock_auto_profile_decision_day_summary(
                    business_date date not null primary key
                )
                """);
        StockSchemaReadinessValidator validator = new StockSchemaReadinessValidator(
                dataSource,
                mock(StockRuntimeIdentity.class)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_auto_participant.behavior_evaluation_mode legacy column removal")
                .hasMessageContaining("stock_auto_profile_decision_day_summary legacy table removal")
                .hasMessageContaining("chk_stock_auto_participant_behavior_evaluation legacy CHECK removal");
    }

}
