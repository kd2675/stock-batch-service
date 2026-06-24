package stock.batch.service.common.act;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockBatchJobControllerTest {

    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StockBatchJobController(stockBatchJobLauncher))
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
}
