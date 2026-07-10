package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBatchApiSurfaceContractTest {

    private static final Set<String> EXPECTED_INTERNAL_API_SURFACE = Set.of(
            "GET /internal/stock-batch/v1/system/status",
            "POST /internal/stock-batch/v1/jobs/market-data/refresh",
            "POST /internal/stock-batch/v1/jobs/order-book-execution/run",
            "POST /internal/stock-batch/v1/jobs/auto-participant-cash-flow/run",
            "GET /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status",
            "PATCH /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status",
            "GET /internal/stock-batch/v1/jobs/runtime-controls",
            "PATCH /internal/stock-batch/v1/jobs/runtime-controls/{jobName}",
            "POST /internal/stock-batch/v1/jobs/auto-market/run",
            "POST /internal/stock-batch/v1/jobs/auto-market-profile-queue/reconcile",
            "POST /internal/stock-batch/v1/jobs/auto-market-order-expiry/run",
            "POST /internal/stock-batch/v1/jobs/listing-auto-market/run",
            "POST /internal/stock-batch/v1/jobs/portfolio-settlement/run",
            "POST /internal/stock-batch/v1/jobs/market-close/rollover",
            "POST /internal/stock-batch/v1/jobs/market-close/rollover/{symbol}",
            "POST /internal/stock-batch/v1/jobs/order-book-orders/cancel-open/{symbol}",
            "POST /internal/stock-batch/v1/jobs/corporate-actions/run"
    );

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void stockBatchInternalApiSurface_matchesInitialEssentialScope() {
        assertThat(internalApiSurface()).isEqualTo(new TreeSet<>(EXPECTED_INTERNAL_API_SURFACE));
    }

    private Set<String> internalApiSurface() {
        return requestMappingHandlerMapping.getHandlerMethods()
                .keySet()
                .stream()
                .flatMap(mappingInfo -> paths(mappingInfo).stream()
                        .filter(path -> path.startsWith("/internal/stock-batch/v1"))
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
}
