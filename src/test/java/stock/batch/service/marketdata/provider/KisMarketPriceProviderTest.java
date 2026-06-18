package stock.batch.service.marketdata.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KisMarketPriceProviderTest {

    private HttpServer server;
    private ExecutorService executorService;
    private KisMarketPriceProvider provider;
    private final AtomicReference<String> priceRequestQuery = new AtomicReference<>();
    private final AtomicReference<String> priceAuthorization = new AtomicReference<>();
    private final AtomicReference<String> priceAppKey = new AtomicReference<>();
    private final AtomicReference<String> priceTrId = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth2/tokenP", exchange -> {
            byte[] response = """
                    {"access_token":"test-token","expires_in":3600}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/uapi/domestic-stock/v1/quotations/inquire-price", exchange -> {
            priceRequestQuery.set(exchange.getRequestURI().getRawQuery());
            priceAuthorization.set(exchange.getRequestHeaders().getFirst("authorization"));
            priceAppKey.set(exchange.getRequestHeaders().getFirst("appkey"));
            priceTrId.set(exchange.getRequestHeaders().getFirst("tr_id"));
            byte[] response = """
                    {"rt_cd":"0","msg1":"정상처리 되었습니다.","output":{"stck_prpr":"71,000"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        executorService = Executors.newSingleThreadExecutor();
        server.setExecutor(executorService);
        server.start();

        provider = new KisMarketPriceProvider(new ObjectMapper());
        ReflectionTestUtils.setField(provider, "baseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(provider, "appKey", "test-app-key");
        ReflectionTestUtils.setField(provider, "appSecret", "test-app-secret");
        ReflectionTestUtils.setField(provider, "marketDivCode", "J");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executorService.shutdownNow();
    }

    @Test
    void fetch_validKisResponses_returnsCurrentPriceQuote() {
        MarketPriceQuote quote = provider.fetch("005930", null);

        assertThat(quote.symbol()).isEqualTo("005930");
        assertThat(quote.currentPrice()).isEqualByComparingTo("71000");
        assertThat(quote.provider()).isEqualTo("kis-openapi");
        assertThat(priceRequestQuery.get()).contains("FID_COND_MRKT_DIV_CODE=J", "FID_INPUT_ISCD=005930");
        assertThat(priceAuthorization.get()).isEqualTo("Bearer test-token");
        assertThat(priceAppKey.get()).isEqualTo("test-app-key");
        assertThat(priceTrId.get()).isEqualTo("FHKST01010100");
    }
}
