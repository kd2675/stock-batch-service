package stock.batch.service.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "stock.batch.internal.token=secret-token",
        "stock.batch.internal.allow-empty-token=false"
})
@ActiveProfiles("test")
class StockBatchInternalApiTokenBoundaryTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void jobRun_configuredInternalTokenAndMissingHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/stock-batch/v1/jobs/order-book-execution/run"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Unauthorized internal batch request")));
    }

    @Test
    void jobRun_configuredInternalTokenAndWrongHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/stock-batch/v1/jobs/order-book-execution/run")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Unauthorized internal batch request")));
    }

    @Test
    void jobRun_configuredInternalTokenAndMatchingHeader_runsJob() throws Exception {
        mockMvc.perform(post("/internal/stock-batch/v1/jobs/order-book-execution/run")
                        .header("X-Internal-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("\"job\":\"order-book-execution\"")));
    }
}
