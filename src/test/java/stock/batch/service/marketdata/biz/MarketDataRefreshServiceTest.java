package stock.batch.service.marketdata.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.marketdata.processor.MarketPriceRefreshProcessor;
import stock.batch.service.batch.marketdata.reader.MarketPriceQuoteReader;
import stock.batch.service.batch.marketdata.reader.MarketPriceRefreshTargetReader;
import stock.batch.service.batch.marketdata.writer.MarketPriceRefreshWriter;
import stock.batch.service.marketdata.provider.MarketPriceProvider;
import stock.batch.service.marketdata.provider.MarketPriceQuote;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataRefreshServiceTest {

    private static final LocalDateTime SIMULATION_PRICE_TIME = LocalDateTime.of(2026, 7, 1, 10, 0);

    private JdbcTemplate jdbcTemplate;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MarketPriceProvider marketPriceProvider;
    private SimulationClockService simulationClockService;
    private MarketPriceRefreshWriter marketPriceRefreshWriter;
    private MarketDataRefreshService marketDataRefreshService;
    private StockPriceRedisPublisher stockPriceRedisPublisher;
    private ObjectMapper objectMapper;
    private MarketSessionFenceService marketSessionFenceService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = TestJdbcTemplateFactory.create();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        marketPriceProvider = mock(MarketPriceProvider.class);
        simulationClockService = mock(SimulationClockService.class);
        objectMapper = new ObjectMapper();
        marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_PRICE_TIME);
        stockPriceRedisPublisher = new StockPriceRedisPublisher(redisTemplate, objectMapper);
        marketPriceRefreshWriter = new MarketPriceRefreshWriter(jdbcTemplate);
        marketDataRefreshService = new MarketDataRefreshService(
                new MarketPriceRefreshTargetReader(jdbcTemplate),
                new MarketPriceQuoteReader(marketPriceProvider),
                new MarketPriceRefreshProcessor(simulationClockService),
                marketPriceRefreshWriter,
                stockPriceRedisPublisher,
                new MarketDataRefreshTransactionExecutor(
                        new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
                        marketSessionFenceService
                ),
                marketSessionFenceService
        );
        ReflectionTestUtils.setField(stockPriceRedisPublisher, "priceCacheTtlSeconds", 60L);

        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_order_book_instrument");
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
        assertThat(queryDateTime("select price_time from stock_price_tick where symbol = '005930'"))
                .isEqualTo(SIMULATION_PRICE_TIME);
        verify(valueOperations).set("stock:price:005930", "70100.00", Duration.ofSeconds(60));
        verifyPublishedPriceEvent("stock.price.005930", "005930", "70100.00", SIMULATION_PRICE_TIME, "test-provider");
    }

    @Test
    void refreshWatchedPrices_providerCall_doesNotHoldDatabaseTransaction() {
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return new MarketPriceQuote(
                            "005930",
                            new BigDecimal("70100.00"),
                            "test-provider",
                            SIMULATION_PRICE_TIME
                    );
                });

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
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
        verifyPublishedPriceEvent("stock.price.123456", "123456", "51000.00", SIMULATION_PRICE_TIME, "test-provider");
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
        verifyPublishedPriceEvent("stock.price.654321", "654321", "83100.00", SIMULATION_PRICE_TIME, "test-provider");
    }

    @Test
    void refreshWatchedPrices_manyOrdersAndHoldings_preAggregatesToOneSymbolWithMaximumReference() {
        insertPendingOrder("reference-order-low", "654321", "71000.00");
        insertPendingOrder("reference-order-high", "654321", "82000.00");
        insertHolding("reference-holder-low", "654321", "76000.00");
        insertHolding("reference-holder-high", "654321", "83000.00");
        when(marketPriceProvider.fetch("654321", new BigDecimal("83000.00")))
                .thenReturn(new MarketPriceQuote(
                        "654321",
                        new BigDecimal("83100.00"),
                        "test-provider",
                        SIMULATION_PRICE_TIME
                ));

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isEqualTo(1);
        verify(marketPriceProvider).fetch("654321", new BigDecimal("83000.00"));
    }

    @Test
    void refreshWatchedPrices_enabledOrderBookSymbol_skipsMarketDataRefresh() {
        insertInstrument("ZQ001", true);
        insertOrderBookInstrument("ZQ001", true);
        insertPrice("ZQ001", "70000.00");
        insertPendingOrder("order-book-pending-order", "ZQ001");
        insertHolding("order-book-holder", "ZQ001", "70000.00");

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isZero();
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ001'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = 'ZQ001'"))
                .isZero();
        org.mockito.Mockito.verifyNoInteractions(valueOperations);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).convertAndSend(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString()
        );
    }

    @Test
    void refreshWatchedPrices_delistedOrderBookHolding_keepsZeroValuePrice() {
        insertOrderBookInstrument("ZQ015", false);
        insertPrice("ZQ015", "0.00");
        insertHolding("delisted-holder", "ZQ015", "70000.00");

        int refreshedCount = marketDataRefreshService.refreshWatchedPrices();

        assertThat(refreshedCount).isZero();
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ015'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        org.mockito.Mockito.verifyNoInteractions(valueOperations);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).convertAndSend(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString()
        );
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
    void refreshWatchedPricesStrict_providerFailure_updatesSuccessfulTargetsAndFailsPhase() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 16);
        insertInstrument("005930", true);
        insertInstrument("000660", true);
        insertPrice("005930", "70000.00");
        insertPrice("000660", "240000.00");
        when(marketPriceProvider.fetch("000660", new BigDecimal("240000.00")))
                .thenReturn(new MarketPriceQuote("000660", new BigDecimal("241000.00"), "test-provider", priceTime));
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(marketDataRefreshService::refreshWatchedPricesStrict)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failedCount=1")
                .hasMessageContaining("005930");

        assertThat(queryDecimal("select current_price from stock_price where symbol = '000660'"))
                .isEqualByComparingTo(new BigDecimal("241000.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void refreshWatchedPricesStrict_marketOpenedBeforeTargetRead_failsWithoutProviderCall() {
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        doThrow(new IllegalStateException("Cannot run pre-open market data refresh while any enabled market is open"))
                .when(marketSessionFenceService)
                .assertMarketLedgerMutationAllowed("pre-open market data refresh");

        assertThatThrownBy(marketDataRefreshService::refreshWatchedPricesStrict)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("while any enabled market is open");
        org.mockito.Mockito.verifyNoInteractions(marketPriceProvider);
    }

    @Test
    void refreshWatchedPrices_sameProviderTickRetried_doesNotDuplicateTick() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 17);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch(eq("005930"), any(BigDecimal.class)))
                .thenReturn(new MarketPriceQuote("005930", new BigDecimal("70300.00"), "test-provider", priceTime));

        marketDataRefreshService.refreshWatchedPrices();
        marketDataRefreshService.refreshWatchedPrices();

        assertThat(queryLong("select count(*) from stock_price_tick where symbol = '005930'"))
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
    void refreshWatchedPricesStrict_redisFailure_updatesDatabaseButFailsPreOpenPhase() {
        LocalDateTime priceTime = LocalDateTime.of(2026, 6, 17, 9, 21);
        insertInstrument("005930", true);
        insertPrice("005930", "70000.00");
        when(marketPriceProvider.fetch("005930", new BigDecimal("70000.00")))
                .thenReturn(new MarketPriceQuote("005930", new BigDecimal("70300.00"), "test-provider", priceTime));
        doThrow(new IllegalStateException("redis down"))
                .when(valueOperations)
                .set("stock:price:005930", "70300.00", Duration.ofSeconds(60));

        assertThatThrownBy(marketDataRefreshService::refreshWatchedPricesStrict)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failedCount=1")
                .hasRootCauseMessage("redis down");

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
        verifyPublishedPriceEvent("stock.price.005930", "005930", "70400.00", SIMULATION_PRICE_TIME, "test-provider");
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
        verifyPublishedPriceEvent("stock.price.005930", "005930", "70450.00", SIMULATION_PRICE_TIME, "test-provider");
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

    private void insertOrderBookInstrument(String symbol, boolean enabled) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares, tradable_shares,
                    enabled, created_at, updated_at
                ) values (?, ?, 'ORDERBOOK', 70000.00, 100000, 0, ?, ?, ?)
                """,
                symbol,
                "상장폐지종목",
                enabled,
                LocalDateTime.now(),
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
        insertPendingOrder(clientOrderId, symbol, "70000.00");
    }

    private void insertPendingOrder(String clientOrderId, String symbol, String limitPrice) {
        Long accountId = accountIdFor("watch-user");
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'VIRTUAL_PRICE', 'BUY', 'LIMIT', 'PENDING', ?, 1, 0, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                new BigDecimal(limitPrice),
                new BigDecimal(limitPrice),
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

    private LocalDateTime queryDateTime(String sql) {
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
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
