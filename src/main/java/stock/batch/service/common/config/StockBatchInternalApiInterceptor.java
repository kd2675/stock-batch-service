package stock.batch.service.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class StockBatchInternalApiInterceptor implements HandlerInterceptor {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Value("${stock.batch.internal.token:}")
    private String internalToken;

    @Value("${stock.batch.internal.allow-empty-token:false}")
    private boolean allowEmptyToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!StringUtils.hasText(internalToken)) {
            if (allowEmptyToken) {
                return true;
            }
            writeUnauthorized(response, "Internal batch token is not configured");
            return false;
        }

        String providedToken = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (constantTimeEquals(internalToken, providedToken)) {
            return true;
        }

        writeUnauthorized(response, "Unauthorized internal batch request");
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"success\":false,\"code\":401,\"message\":\"%s\"}",
                message
        ));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
