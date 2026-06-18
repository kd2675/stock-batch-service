package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

class StockBatchInternalApiInterceptorTest {

    private final StockBatchInternalApiInterceptor interceptor = new StockBatchInternalApiInterceptor();

    @Test
    void preHandle_emptyConfiguredTokenAndEmptyTokenAllowed_allowsRequestWithoutHeader() throws Exception {
        ReflectionTestUtils.setField(interceptor, "internalToken", "");
        ReflectionTestUtils.setField(interceptor, "allowEmptyToken", true);

        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean allowed = ((HandlerInterceptor) interceptor).preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
    }

    @Test
    void preHandle_emptyConfiguredTokenAndEmptyTokenNotAllowed_rejectsRequest() throws Exception {
        ReflectionTestUtils.setField(interceptor, "internalToken", "");
        ReflectionTestUtils.setField(interceptor, "allowEmptyToken", false);

        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean allowed = ((HandlerInterceptor) interceptor).preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Internal batch token is not configured");
    }

    @Test
    void preHandle_configuredTokenAndMissingHeader_rejectsRequest() throws Exception {
        ReflectionTestUtils.setField(interceptor, "internalToken", "secret-token");
        ReflectionTestUtils.setField(interceptor, "allowEmptyToken", false);

        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean allowed = ((HandlerInterceptor) interceptor).preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unauthorized internal batch request");
    }

    @Test
    void preHandle_configuredTokenAndMatchingHeader_allowsRequest() throws Exception {
        ReflectionTestUtils.setField(interceptor, "internalToken", "secret-token");
        ReflectionTestUtils.setField(interceptor, "allowEmptyToken", false);

        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Internal-Token", "secret-token");
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean allowed = ((HandlerInterceptor) interceptor).preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
    }
}
