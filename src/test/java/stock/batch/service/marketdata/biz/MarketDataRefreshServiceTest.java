package stock.batch.service.marketdata.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.marketdata.processor.MarketPriceRefreshProcessor;
import stock.batch.service.batch.marketdata.reader.MarketPriceQuoteReader;
import stock.batch.service.batch.marketdata.reader.MarketPriceRefreshTargetReader;
import stock.batch.service.batch.marketdata.writer.MarketPriceRefreshWriter;
import stock.batch.service.marketdata.provider.MarketPriceProvider;
import stock.batch.service.marketdata.provider.MarketPriceQuote;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataRefreshServiceTest {

    private JdbcTemplate jdbcTemplate;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MarketPriceProvider marketPriceProvider;
    private MarketPriceRefreshWriter marketPriceRefreshWriter;
    private MarketDataRefreshService marketDataRefreshService;
    private StockPriceRedisPublisher stockPriceRedisPublisher;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = TestJdbcTemplateFactory.create();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        marketPriceProvider = mock(MarketPriceProvider.class);
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        stockPriceRedisPublisher = new StockPriceRedisPublisher(redisTemplate, objectMapper);
        marketPriceRefreshWriter = new MarketPriceRefreshWriter(jdbcTemplate);
        marketDataRefreshService = new MarketDataRefreshService(
                new MarketPriceRefreshTargetReader(jdbcTemplate),
                new MarketPriceQuoteReader(marketPriceProvider),
                new MarketPriceRefreshProcessor(),
                marketPriceRefreshWriter,
                stockPriceRedisPublisher
        );
        ReflectionTestUtils.setField(stockPriceRedisPublisher, "priceCacheTtlSeconds", 60L);

        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_instrument");
    }

    @Test
    void refreshWatchedPrices_providerQuote_updatesLatestPriceTickAndRedis() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 5);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("005930", new BigDecimal("70100.00"), "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70100.00"));
        assertThat(queryString("select provider from stock_price where symbol = '005930'"))
                .isEqualTo("test-provider");
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '005930'"))
                .isEqualTo(1L);
        assertThat(queryDecimal("select price from stock_price_tick where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70100.00"));
        verify(valueOperations).set("stock:price:005930", "70100.00", Duration.ofSeconds(60));
        verifyPublishedPriceEvent("stock.price.005930", "005930", "70100.00", priceTime, "test-provider");
    }

    @Test
    void refreshWatchedPrices_pendingOrderSymbol_refreshesEvenWhenInstrumentDisabled() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 10);
        insertInstrument("005930", false);
        insertPrice("005930", "70000.00");
        insertPendingOrder("pending-order", "005930");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("005930", new BigDecimal("70200.00"), "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70200.00"));
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '005930'"))
                .isEqualTo(1L);
    }

    @Test
    void refreshWatchedPrices_pendingOrderSymbolWithoutPrice_insertsLatestPriceRow() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 12);
        insertPendingOrder("pending-without-price", "123456");
        when(marketPriceProvider.fetch("123456", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("123456", new BigDecimal("51000.00"), "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '123456'"))
                .isEqualByComparingTo(new BigDecimal("51000.00"));
        assertThat(queryDecimal("select previous_close from stock_price where symbol = '123456'"))
                .isEqualByComparingTo(new BigDecimal("51000.00"));
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '123456'"))
                .isEqualTo(1L);
        verify(valueOperations).set("stock:price:123456", "51000.00", Duration.ofSeconds(60));
        verifyPublishedPriceEvent("stock.price.123456", "123456", "51000.00", priceTime, "test-provider");
    }

    @Test
    void refreshWatchedPrices_holdingSymbolWithoutPrice_usesAveragePriceAsReference() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 13);
        insertHolding("holder", "654321", "83000.00");
        when(marketPriceProvider.fetch("654321", new BigDecimal("83000.00")))
                .thenReturn(new MarketPriceQuote("654321", new BigDecimal("83100.00"), "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '654321'"))
                .isEqualByComparingTo(new BigDecimal("83100.00"));
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '654321'"))
                .isEqualTo(1L);
        verify(valueOperations).set("stock:price:654321", "83100.00", Duration.ofSeconds(60));
        verifyPublishedPriceEvent("stock.price.654321", "654321", "83100.00", priceTime, "test-provider");
    }

    @Test
    void refreshWatchedPrices_providerFailure_skipsFailedSymbolAndContinues() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 15);
        insertInstrument("005930", true);
        insertInstrument("000660", true);
        insertPrice("005930", "70000.00");
        insertPrice("000660", "240000.00");
        when(marketPriceProvider.fetch("000660", new BigDecimal("240000.00")))
                .thenReturn(new MarketPriceQuote("000660", new BigDecimal("241000.00"), "test-provider", priceTime));
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenThrow(new IllegalStateException("provider down"));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '000660'"))
                .isEqualByComparingTo(new BigDecimal("241000.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_price_tick"))
                .isEqualTo(1L);
    }

    @Test
    void refreshWatchedPrices_redisFailure_stillUpdatesDatabaseAndCountsRefresh() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 20);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("005930", new BigDecimal("70300.00"), "test-provider", priceTime));
        doThrow(new IllegalStateException("redis down"))
                .when(valueOperations)
                .set("stock:price:005930", "70300.00", Duration.ofSeconds(60));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70300.00"));
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '005930'"))
                .isEqualTo(1L);
    }

    @Test
    void refreshWatchedPrices_providerReturnsMismatchedSymbol_skipsWithoutUpdatingDatabaseOrRedis() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 25);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("000660", new BigDecimal("70300.00"), "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isZero();
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_price_tick"))
                .isZero();
        org.mockito.Mockito.verifyNoInteractions(valueOperations);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).convertAndSend(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString()
        );
    }

    @Test
    void refreshWatchedPrices_providerReturnsSymbolWithWhitespace_normalizesBeforeUpdatingDatabaseAndRedis() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 28);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote(" 005930 ", new BigDecimal("70400.00"), " test-provider ", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70400.00"));
        assertThat(queryString("select provider from stock_price where symbol = '005930'"))
                .isEqualTo("test-provider");
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '005930'"))
                .isEqualTo(1L);
        verify(valueOperations).set("stock:price:005930", "70400.00", Duration.ofSeconds(60));
        verifyPublishedPriceEvent("stock.price.005930", "005930", "70400.00", priceTime, "test-provider");
    }

    @Test
    void refreshWatchedPrices_nonPositiveCacheTtl_usesMinimumOneSecondTtl() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 29);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        ReflectionTestUtils.setField(stockPriceRedisPublisher, "priceCacheTtlSeconds", 0L);
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("005930", new BigDecimal("70450.00"), "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        verify(valueOperations).set("stock:price:005930", "70450.00", Duration.ofSeconds(1));
        verifyPublishedPriceEvent("stock.price.005930", "005930", "70450.00", priceTime, "test-provider");
    }

    @Test
    void refreshWatchedPrices_providerReturnsNonPositivePrice_skipsWithoutUpdatingDatabaseOrRedis() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 30);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("005930", BigDecimal.ZERO, "test-provider", priceTime));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isZero();
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_price_tick"))
                .isZero();
        org.mockito.Mockito.verifyNoInteractions(valueOperations);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).convertAndSend(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString()
        );
    }

    private void verifyPublishedPriceEvent(
            String channel,
            String symbol,
            String currentPrice,
            LocalDateTime priceTime,
            String provider
    ) {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).convertAndSend(eq(channel), payloadCaptor.capture());
        try {
            JsonNode payload = objectMapper.readTree(String.valueOf(payloadCaptor.getValue()));
            assertThat(payload.get("symbol").asText()).isEqualTo(symbol);
            assertThat(payload.get("currentPrice").decimalValue()).isEqualByComparingTo(new BigDecimal(currentPrice));
            assertThat(payload.get("priceTime").asText()).isEqualTo(priceTime.toString());
            assertThat(payload.get("provider").asText()).isEqualTo(provider);
        } catch (IOException ex) {
            throw new AssertionError("Expected Redis price event payload to be JSON", ex);
        }
    }

    private void insertInstrument(String symbol, boolean enabled) {
        jdbcTemplate.update(
                "insert into stock_instrument(symbol, name, market, enabled, created_at) values (?, ?, 'KOSPI', ?, ?)",
                symbol,
                "테스트종목",
                enabled,
                LocalDateTime.now()
        );
    }

    private void insertPrice(String symbol, String currentPrice) {
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close, price_time, provider) values (?, ?, ?, ?, 'seed')",
                symbol,
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                LocalDateTime.now()
        );
    }

    private void insertPendingOrder(String clientOrderId, String symbol) {
        Long accountId = accountIdFor("watch-user");
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'BUY', 'LIMIT', 'PENDING', 70000.00, 1, 0, 70000.00, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertHolding(String userKey, String symbol, String averagePrice) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (?, ?, 1, 0, ?, ?)
                """,
                accountId,
                symbol,
                new BigDecimal(averagePrice),
                LocalDateTime.now()
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private Long accountIdFor(String userKey) {
        Long existing = jdbcTemplate.queryForObject(
                "select count(*) from stock_account where user_key = ?",
                Long.class,
                userKey
        );
        if (existing == null || existing == 0) {
            jdbcTemplate.update(
                    "insert into stock_account(user_key, cash_balance, created_at, updated_at) values (?, 10000000.00, ?, ?)",
                    userKey,
                    LocalDateTime.now(),
                    LocalDateTime.now()
            );
            jdbcTemplate.update(
                    """
                    insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                    select id, 'DEPOSIT', 10000000.00, 'OPENING_GRANT', 'SYSTEM', ?
                    from stock_account
                    where user_key = ?
                    """,
                    LocalDateTime.now(),
                    userKey
            );
        }
        return jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                userKey
        );
    }
}
