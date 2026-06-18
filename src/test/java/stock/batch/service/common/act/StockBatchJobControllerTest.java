package stock.batch.service.common.act;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import stock.batch.service.common.biz.StockBatchJobService;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockBatchJobControllerTest {

    private final StockBatchJobService stockBatchJobService = mock(StockBatchJobService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StockBatchJobController(stockBatchJobService))
            .build();

    @Test
    void refreshMarketData_postRequest_returnsJobRunResponse() throws Exception {
        when(stockBatchJobService.refreshMarketData()).thenReturn(response("market-data-refresh", "n/a"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/market-data/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.job").value("market-data-refresh"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.message").value("Job completed"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void executePendingOrders_postRequest_returnsExecutionMode() throws Exception {
        when(stockBatchJobService.executePendingOrders()).thenReturn(response("order-execution", "internal-order-book"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/order-execution/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("order-execution"))
                .andExpect(jsonPath("$.data.executionMode").value("internal-order-book"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    @Test
    void settlePortfolios_postRequest_returnsJobRunResponse() throws Exception {
        when(stockBatchJobService.settlePortfolios()).thenReturn(response("portfolio-settlement", "n/a"));

        mockMvc.perform(post("/internal/stock-batch/v1/jobs/portfolio-settlement/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job").value("portfolio-settlement"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.processedCount").value(7));
    }

    private StockBatchJobRunResponse response(String job, String executionMode) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 12, 0);
        return new StockBatchJobRunResponse(job, "COMPLETED", executionMode, 7, "Job completed", now, now);
    }
}
