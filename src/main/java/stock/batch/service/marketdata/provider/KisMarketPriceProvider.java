package stock.batch.service.marketdata.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "stock.batch.market-data", name = "provider", havingValue = "kis")
public class KisMarketPriceProvider implements MarketPriceProvider {

    private static final String PROVIDER_NAME = "kis-openapi";
    private static final String TOKEN_PATH = "/oauth2/tokenP";
    private static final String INQUIRE_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String INQUIRE_PRICE_TR_ID = "FHKST01010100";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    @Value("${stock.batch.market-data.kis.base-url:https://openapi.koreainvestment.com:9443}")
    private String baseUrl;

    @Value("${stock.batch.market-data.kis.app-key:}")
    private String appKey;

    @Value("${stock.batch.market-data.kis.app-secret:}")
    private String appSecret;

    @Value("${stock.batch.market-data.kis.market-div-code:J}")
    private String marketDivCode;

    private volatile AccessToken cachedToken;

    public KisMarketPriceProvider(
            ObjectMapper objectMapper,
            @Value("${stock.batch.market-data.kis.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${stock.batch.market-data.kis.request-timeout-ms:10000}") long requestTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(positiveDuration(connectTimeoutMs, "KIS connect timeout"))
                .build();
        this.requestTimeout = positiveDuration(requestTimeoutMs, "KIS request timeout");
    }

    KisMarketPriceProvider(ObjectMapper objectMapper) {
        this(objectMapper, 5000, 10000);
    }

    @Override
    public MarketPriceQuote fetch(String symbol, BigDecimal previousPrice) {
        validateCredentials();
        String accessToken = getAccessToken();
        JsonNode output = requestCurrentPrice(symbol, accessToken);
        BigDecimal currentPrice = parsePrice(output.path("stck_prpr").asText());
        return new MarketPriceQuote(symbol, currentPrice, PROVIDER_NAME, LocalDateTime.now());
    }

    private String getAccessToken() {
        AccessToken token = cachedToken;
        if (token != null && token.isValid()) {
            return token.value();
        }
        synchronized (this) {
            token = cachedToken;
            if (token != null && token.isValid()) {
                return token.value();
            }
            cachedToken = requestAccessToken();
            return cachedToken.value();
        }
    }

    private AccessToken requestAccessToken() {
        try {
            String requestBody = objectMapper.writeValueAsString(new TokenRequest("client_credentials", appKey, appSecret));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBaseUrl() + TOKEN_PATH))
                    .timeout(requestTimeout)
                    .header("content-type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = send(request, "KIS token request");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("KIS token request failed: status=" + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String accessToken = root.path("access_token").asText();
            long expiresIn = root.path("expires_in").asLong(3600);
            if (!StringUtils.hasText(accessToken)) {
                throw new IllegalStateException("KIS token response did not include access_token");
            }
            return new AccessToken(accessToken, Instant.now().plusSeconds(Math.max(60, expiresIn - 60)));
        } catch (IOException ex) {
            throw new IllegalStateException("KIS token response parsing failed", ex);
        }
    }

    private JsonNode requestCurrentPrice(String symbol, String accessToken) {
        try {
            String query = "FID_COND_MRKT_DIV_CODE=" + encode(marketDivCode)
                    + "&FID_INPUT_ISCD=" + encode(symbol);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBaseUrl() + INQUIRE_PRICE_PATH + "?" + query))
                    .timeout(requestTimeout)
                    .header("authorization", "Bearer " + accessToken)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .header("tr_id", INQUIRE_PRICE_TR_ID)
                    .header("accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = send(request, "KIS price request: symbol=" + symbol);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("KIS price request failed: symbol=" + symbol + ", status=" + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"0".equals(root.path("rt_cd").asText())) {
                throw new IllegalStateException("KIS price request rejected: symbol=" + symbol + ", message=" + root.path("msg1").asText());
            }
            JsonNode output = root.path("output");
            if (output.isMissingNode() || !StringUtils.hasText(output.path("stck_prpr").asText())) {
                throw new IllegalStateException("KIS price response did not include stck_prpr: symbol=" + symbol);
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("KIS price response parsing failed: symbol=" + symbol, ex);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException ex) {
            throw new IllegalStateException(operation + " timed out", ex);
        } catch (IOException ex) {
            throw new IllegalStateException(operation + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", ex);
        }
    }

    private void validateCredentials() {
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            throw new IllegalStateException("KIS provider requires stock.batch.market-data.kis.app-key and app-secret");
        }
    }

    private BigDecimal parsePrice(String value) {
        String normalized = value.replace(",", "").trim();
        BigDecimal price = new BigDecimal(normalized);
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("KIS price must be positive");
        }
        return price;
    }

    private String normalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Duration positiveDuration(long millis, String label) {
        if (millis <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private record TokenRequest(String grant_type, String appkey, String appsecret) {
    }

    private record AccessToken(String value, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
