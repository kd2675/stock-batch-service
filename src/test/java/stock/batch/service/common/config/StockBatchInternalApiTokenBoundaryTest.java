package stock.batch.service.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "stock.batch.internal.token=secret-token",
        "stock.batch.internal.allow-empty-token=false"
})
@ActiveProfiles("test")
class StockBatchInternalApiTokenBoundaryTest {

    private static final List<Endpoint> PROTECTED_JOB_ENDPOINTS = List.of(
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/market-data/refresh", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/virtual-price-execution/run", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/order-book-execution/run", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run", null),
            new Endpoint(HttpMethod.GET, "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status", null),
            new Endpoint(HttpMethod.PATCH, "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status", """
                    {
                      "runtimeEnabled": false,
                      "updatedBy": "stock-admin"
                    }
                    """),
            new Endpoint(HttpMethod.GET, "/internal/stock-batch/v1/jobs/runtime-controls", null),
            new Endpoint(HttpMethod.PATCH, "/internal/stock-batch/v1/jobs/runtime-controls/auto-market", "/internal/stock-batch/v1/jobs/runtime-controls/{jobName}", """
                    {
                      "runtimeEnabled": false,
                      "updatedBy": "stock-admin"
                    }
                    """),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/auto-market/run", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/portfolio-settlement/run", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/market-close/rollover", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/market-close/rollover/005930", "/internal/stock-batch/v1/jobs/market-close/rollover/{symbol}", null),
            new Endpoint(HttpMethod.POST, "/internal/stock-batch/v1/jobs/corporate-actions/run", null)
    );

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void allJobEndpoints_configuredInternalTokenAndMissingHeader_returnUnauthorized() throws Exception {
        for (Endpoint endpoint : PROTECTED_JOB_ENDPOINTS) {
            var builder = request(endpoint.method(), endpoint.path());
            if (endpoint.body() != null) {
                builder.contentType("application/json").content(endpoint.body());
            }

            mockMvc.perform(builder)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(containsString("Unauthorized internal batch request")));
        }
    }

    @Test
    void protectedJobEndpoints_coverEveryInternalJobApiSurface() {
        Set<String> protectedRoutes = PROTECTED_JOB_ENDPOINTS.stream()
                .map(Endpoint::route)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(protectedRoutes).isEqualTo(internalJobApiSurface());
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
    void runtimeControls_configuredInternalTokenAndMissingHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/stock-batch/v1/jobs/runtime-controls"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Unauthorized internal batch request")));
    }

    @Test
    void runtimeControlsPatch_configuredInternalTokenAndMissingHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/runtime-controls/auto-market")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Unauthorized internal batch request")));
    }

    @Test
    void cashFlowStatusPatch_configuredInternalTokenAndMissingHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(patch("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status")
                        .contentType("application/json")
                        .content("""
                                {
                                  "runtimeEnabled": false,
                                  "updatedBy": "stock-admin"
                                }
                                """))
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

    private Set<String> internalJobApiSurface() {
        return requestMappingHandlerMapping.getHandlerMethods()
                .keySet()
                .stream()
                .flatMap(mappingInfo -> paths(mappingInfo).stream()
                        .filter(path -> path.startsWith("/internal/stock-batch/v1/jobs/"))
                        .flatMap(path -> methods(mappingInfo).stream().map(method -> method + " " + path)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> paths(RequestMappingInfo mappingInfo) {
        if (mappingInfo.getPathPatternsCondition() == null) {
            return Set.of();
        }
        return mappingInfo.getPathPatternsCondition().getPatternValues();
    }

    private Set<RequestMethod> methods(RequestMappingInfo mappingInfo) {
        Set<RequestMethod> methods = mappingInfo.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return Set.of(RequestMethod.GET);
        }
        return methods;
    }

    private record Endpoint(HttpMethod method, String path, String surfacePath, String body) {
        private Endpoint(HttpMethod method, String path, String body) {
            this(method, path, path, body);
        }

        private String route() {
            return method + " " + surfacePath;
        }
    }
}
