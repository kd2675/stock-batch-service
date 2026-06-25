package stock.batch.service.batch.common.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchJobRuntimeControlTest {

    @Test
    void status_missingRow_createsEnabledControlRowInDatabase() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobRuntimeControl runtimeControl = new BatchJobRuntimeControl(jdbcTemplate);

        BatchJobRuntimeStatus status = runtimeControl.status("auto-market", true);

        assertThat(status.jobName()).isEqualTo("auto-market");
        assertThat(status.runtimeEnabled()).isTrue();
        assertThat(status.effectiveEnabled()).isTrue();
        assertThat(status.updatedBy()).isEqualTo("SYSTEM");
        assertThat(controlRowCount(jdbcTemplate, "auto-market")).isEqualTo(1L);
        assertThat(runtimeEnabled(jdbcTemplate, "auto-market")).isTrue();
    }

    @Test
    void update_runtimeDisabled_isSharedThroughDatabaseAcrossInstances() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobRuntimeControl firstInstance = new BatchJobRuntimeControl(jdbcTemplate);
        BatchJobRuntimeControl secondInstance = new BatchJobRuntimeControl(jdbcTemplate);

        BatchJobRuntimeStatus updated = firstInstance.update("auto-market", true, false, "stock-admin");

        assertThat(updated.runtimeEnabled()).isFalse();
        assertThat(updated.effectiveEnabled()).isFalse();
        assertThat(updated.updatedBy()).isEqualTo("stock-admin");
        assertThat(secondInstance.shouldRunScheduledJob("auto-market", true)).isFalse();
        assertThat(secondInstance.status("auto-market", true).runtimeEnabled()).isFalse();
        assertThat(controlRowCount(jdbcTemplate, "auto-market")).isEqualTo(1L);
    }

    @Test
    void status_schedulerConfiguredFalse_doesNotDisableStoredRuntimeRow() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobRuntimeControl runtimeControl = new BatchJobRuntimeControl(jdbcTemplate);

        BatchJobRuntimeStatus status = runtimeControl.status("market-close-rollover", false);

        assertThat(status.schedulerConfigured()).isFalse();
        assertThat(status.runtimeEnabled()).isTrue();
        assertThat(status.effectiveEnabled()).isFalse();
        assertThat(runtimeEnabled(jdbcTemplate, "market-close-rollover")).isTrue();
    }

    @Test
    void status_blankJobName_rejectsBeforeWritingDatabase() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobRuntimeControl runtimeControl = new BatchJobRuntimeControl(jdbcTemplate);

        assertThatThrownBy(() -> runtimeControl.status("  ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jobName is required");
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_batch_job_control", Long.class))
                .isZero();
    }

    private JdbcTemplate createJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:batch_job_runtime_control_test_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table if not exists stock_batch_job_control (
                  job_name varchar(100) not null primary key,
                  runtime_enabled boolean not null default true,
                  updated_by varchar(64),
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  constraint chk_stock_batch_job_control_name check (job_name <> '')
                )
                """);
        return template;
    }

    private Long controlRowCount(JdbcTemplate jdbcTemplate, String jobName) {
        return jdbcTemplate.queryForObject(
                "select count(*) from stock_batch_job_control where job_name = ?",
                Long.class,
                jobName
        );
    }

    private Boolean runtimeEnabled(JdbcTemplate jdbcTemplate, String jobName) {
        return jdbcTemplate.queryForObject(
                "select runtime_enabled from stock_batch_job_control where job_name = ?",
                Boolean.class,
                jobName
        );
    }
}
