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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "stock.batch.internal.token=",
        "stock.batch.internal.allow-empty-token=false"
})
@ActiveProfiles("test")
class StockBatchInternalApiBoundaryTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void systemStatus_withoutInternalToken_isPublic() throws Exception {
        mockMvc.perform(get("/internal/stock-batch/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stock-batch-service")));
    }

    @Test
    void jobRun_withoutConfiguredInternalTokenAndEmptyTokenNotAllowed_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/stock-batch/v1/jobs/order-book-execution/run"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Internal batch token is not configured")));
    }
}
