package stock.batch.service.common.act;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.common.policy.BatchJobRuntimeCatalog;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockBatchJobControllerTest {

    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final JdbcTemplate jdbcTemplate = createJdbcTemplate();
    private final BatchJobRuntimeControl batchJobRuntimeControl = new BatchJobRuntimeControl(jdbcTemplate);
    private final AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl =
            new AutoParticipantCashFlowRuntimeControl(batchJobRuntimeControl, true);
    private final BatchJobRuntimeCatalog batchJobRuntimeCatalog = createRuntimeCatalog();
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StockBatchJobController(
                    stockBatchJobLauncher,
                    autoParticipantCashFlowRuntimeControl,
                    batchJobRuntimeCatalog
            ))
            .build();

    @Test
    void refreshMarketData_postRequest_returnsJobRunResponse() throws Exception {
        when(stockBatchJobLauncher.refreshMarketData()).thenReturn(response("market-data-refresh", "n/a"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/market-data/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.job").value("market-data-refresh"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.message").value("Job completed"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void executeVirtualPriceOrders_postRequest_returnsExecutionMode() throws Exception {
        when(stockBatchJobLauncher.executeVirtualPriceOrders()).thenReturn(response("virtual-price-execution", "virtual-price"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/virtual-price-execution/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("virtual-price-execution"))
                .andExpect(jsonPath("$.data.executionMode").value("virtual-price"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void executeOrderBookOrders_postRequest_returnsExecutionMode() throws Exception {
        when(stockBatchJobLauncher.executeOrderBookOrders()).thenReturn(response("order-book-execution", "order-book"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/order-book-execution/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("order-book-execution"))
                .andExpect(jsonPath("$.data.executionMode").value("order-book"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void fundAutoParticipants_postRequest_returnsExecutionMode() throws Exception {
        when(stockBatchJobLauncher.fundAutoParticipants()).thenReturn(response("auto-participant-cash-flow", "recurring-cash"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("auto-participant-cash-flow"))
                .andExpect(jsonPath("$.data.executionMode").value("recurring-cash"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void getAutoParticipantCashFlowStatus_getRequest_returnsRuntimeStatus() throws Exception {
        mockMvc.perform(get("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schedulerConfigured").value(true))
                .andExpect(jsonPath("$.data.runtimeEnabled").value(true))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(true));
    }

    @Test
    void updateAutoParticipantCashFlowStatus_patchRequest_updatesRuntimeStatus() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schedulerConfigured").value(true))
                .andExpect(jsonPath("$.data.runtimeEnabled").value(false))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(false))
                .andExpect(jsonPath("$.data.updatedBy").value("stock-admin"));
    }

    @Test
    void updateAutoParticipantCashFlowStatus_missingRuntimeEnabled_returnsBadRequest() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status")
                        .contentType("application/json")
                        .content("""
                                {
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRuntimeControls_getRequest_returnsAllBatchJobRuntimeStatuses() throws Exception {
        mockMvc.perform(get("/internal/stock-batch/v1/jobs/runtime-controls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].jobName").value("market-data-refresh"))
                .andExpect(jsonPath("$.data[5].jobName").value("auto-participant-cash-flow"))
                .andExpect(jsonPath("$.data[7].jobName").value("portfolio-settlement"));
    }

    @Test
    void updateRuntimeControl_patchRequest_updatesSharedRuntimeControlRow() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/runtime-controls/auto-participant-cash-flow")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value("auto-participant-cash-flow"))
                .andExpect(jsonPath("$.data.runtimeEnabled").value(false))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(false))
                .andExpect(jsonPath("$.data.updatedBy").value("stock-admin"));

        mockMvc.perform(get("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimeEnabled").value(false));
    }

    @Test
    void runtimeCatalog_jobNameWithWhitespace_usesNormalizedRuntimeControlKey() {
        var response = batchJobRuntimeCatalog.update(" auto-market ", false, "stock-admin");

        org.assertj.core.api.Assertions.assertThat(response.jobName()).isEqualTo("auto-market");
        org.assertj.core.api.Assertions.assertThat(response.runtimeEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(response.effectiveEnabled()).isFalse();
    }

    @Test
    void updateRuntimeControl_unknownJobName_returnsNotFound() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/runtime-controls/not-a-job")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRuntimeControl_blankJobName_returnsBadRequest() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/runtime-controls/%20")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRuntimeControl_encodedWhitespaceAroundKnownJobName_usesNormalizedRuntimeControlKey() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/runtime-controls/%20auto-market%20")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value("auto-market"))
                .andExpect(jsonPath("$.data.runtimeEnabled").value(false))
                .andExpect(jsonPath("$.data.effectiveEnabled").value(false));
    }

    @Test
    void updateRuntimeControl_missingRuntimeEnabled_returnsBadRequest() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/runtime-controls/auto-market")
                        .contentType("application/json")
                        .content("""
                                {
                                  "updatedBy": "stock-admin"
                                }
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void manualRunApi_runsJobEvenWhenRuntimeControlDisablesScheduledExecution() throws Exception {
        batchJobRuntimeCatalog.update("auto-market", false, "stock-admin");
        when(stockBatchJobLauncher.runAutoMarket()).thenReturn(response("auto-market", "order-book"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/auto-market/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("auto-market"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(stockBatchJobLauncher).runAutoMarket();
    }

    @Test
    void settlePortfolios_postRequest_returnsJobRunResponse() throws Exception {
        when(stockBatchJobLauncher.settlePortfolios()).thenReturn(response("portfolio-settlement", "n/a"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/portfolio-settlement/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("portfolio-settlement"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void rolloverClosingPrices_postRequest_returnsJobRunResponse() throws Exception {
        when(stockBatchJobLauncher.rolloverClosingPrices()).thenReturn(response("market-close-rollover", "price-limit-base"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/market-close/rollover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("market-close-rollover"))
                .andExpect(jsonPath("$.data.executionMode").value("price-limit-base"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void applyCorporateActions_postRequest_returnsJobRunResponse() throws Exception {
        when(stockBatchJobLauncher.applyCorporateActions()).thenReturn(response("corporate-actions", "order-book"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/corporate-actions/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("corporate-actions"))
                .andExpect(jsonPath("$.data.executionMode").value("order-book"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    private StockBatchJobRunResponse response(String job, String executionMode) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 12, 0);
        return new StockBatchJobRunResponse(job, "COMPLETED", executionMode, 7, "Job completed", now, now);
    }

    private BatchJobRuntimeCatalog createRuntimeCatalog() {
        return new BatchJobRuntimeCatalog(
                batchJobRuntimeControl,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true
        );
    }

    private JdbcTemplate createJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:stock_batch_job_controller_test_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(UUID.randomUUID()));
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
}
