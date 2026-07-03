package stock.batch.service.batch.common.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

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
        assertThat(schedulerConfigured(jdbcTemplate, "market-close-rollover")).isFalse();
    }

    @Test
    void status_schedulerConfiguredValueChanges_synchronizesStoredConfiguredFlag() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobRuntimeControl runtimeControl = new BatchJobRuntimeControl(jdbcTemplate);

        assertThat(runtimeControl.status("auto-market", false).schedulerConfigured()).isFalse();
        assertThat(schedulerConfigured(jdbcTemplate, "auto-market")).isFalse();

        assertThat(runtimeControl.status("auto-market", true).schedulerConfigured()).isTrue();
        assertThat(schedulerConfigured(jdbcTemplate, "auto-market")).isTrue();
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
        return BatchTestDatabaseFactory.createJobControlJdbcTemplate("batch_job_runtime_control_test");
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

    private Boolean schedulerConfigured(JdbcTemplate jdbcTemplate, String jobName) {
        return jdbcTemplate.queryForObject(
                "select scheduler_configured from stock_batch_job_control where job_name = ?",
                Boolean.class,
                jobName
        );
    }
}
