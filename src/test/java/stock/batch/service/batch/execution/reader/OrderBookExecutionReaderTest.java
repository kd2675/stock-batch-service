package stock.batch.service.batch.execution.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookMatchCandidate;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

class OrderBookExecutionReaderTest {

    private JdbcTemplate jdbcTemplate;
    private OrderBookExecutionReader reader;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("order_book_execution_reader_test")
        );
        jdbcTemplate.execute("""
                create table stock_order_book_market_config (
                    symbol varchar(20) not null,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_instrument (
                    symbol varchar(20) not null,
                    enabled boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_account (
                    id bigint not null,
                    cash_balance decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_holding (
                    id bigint not null,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint not null,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    side varchar(10) not null,
                    order_type varchar(20) not null,
                    market_type varchar(20) not null,
                    status varchar(30) not null,
                    limit_price decimal(19, 2),
                    quantity bigint not null,
                    filled_quantity bigint not null,
                    average_fill_price decimal(19, 2),
                    reserved_cash decimal(19, 2),
                    funding_budget_type varchar(20),
                    created_at timestamp not null
                )
                """);
        reader = new OrderBookExecutionReader(jdbcTemplate);
    }

    @Test
    void findExecutableSymbols_returnsOnlyOpenMarketsWithCrossableDifferentAccountPair() {
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK001', true, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK002', true, 'CLOSED')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK003', true, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK001', true)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK002', true)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK003', true)");
        insertOrder(1L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 2);
        insertOrder(3L, 30L, "STOCK002", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 3);
        insertOrder(4L, 40L, "STOCK002", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 4);
        insertOrder(5L, 50L, "STOCK003", "BUY", "LIMIT", "PENDING", new BigDecimal("69000.00"), 10L, 0L, 5);
        insertOrder(6L, 60L, "STOCK003", "SELL", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 6);

        List<String> symbols = reader.findExecutableSymbolCandidates(10);

        assertThat(symbols).containsExactly("STOCK001");
    }

    @Test
    void findOpenOrderBookSymbols_returnsOnlyEnabledOpenMarketAndInstrumentWithinLimit() {
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK001', true, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK002', true, 'CLOSED')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK003', false, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK004', true, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK005', true, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK001', true)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK002', true)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK003', true)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK004', false)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK005', true)");

        List<String> firstPage = reader.findOpenOrderBookSymbolsAfter("", 1);
        List<String> secondPage = reader.findOpenOrderBookSymbolsAfter("STOCK001", 1);

        assertThat(List.of(firstPage, secondPage))
                .containsExactly(List.of("STOCK001"), List.of("STOCK005"));
    }

    @Test
    void findBestMatchCandidate_prioritizesMarketBuyAndBestLimitSell() {
        insertOrder(1L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70100.00"), 10L, 0L, 2);
        insertOrder(3L, 30L, "STOCK001", "BUY", "MARKET", "PENDING", null, 10L, 0L, 3);
        insertOrder(4L, 40L, "STOCK001", "BUY", "LIMIT", "FILLED", new BigDecimal("80000.00"), 10L, 10L, 4);
        insertOrder(5L, 50L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 5);

        OrderBookMatchCandidate candidate = reader.findBestMatchCandidate("STOCK001", 10).orElseThrow();

        assertThat(candidate).isEqualTo(new OrderBookMatchCandidate(3L, 30L, 5L, 50L));
    }

    @Test
    void findBestMatchCandidate_skipsSelfTradeAndUsesNextCrossableBuy() {
        insertOrder(1L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 1);
        insertOrder(2L, 10L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69000.00"), 10L, 0L, 2);
        insertOrder(3L, 20L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("69500.00"), 10L, 0L, 3);

        OrderBookMatchCandidate candidate = reader.findBestMatchCandidate("STOCK001", 10).orElseThrow();

        assertThat(candidate).isEqualTo(new OrderBookMatchCandidate(3L, 20L, 2L, 10L));
    }

    @Test
    void findMatchOrdersForUpdate_revalidatesBothOrdersAndReturnsPrimaryKeyOrder() {
        insertOrder(1L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 2);

        List<OrderBookOrderRow> orders = reader.findMatchOrdersForUpdate(new OrderBookMatchCandidate(1L, 10L, 2L, 20L));

        assertThat(orders).extracting(OrderBookOrderRow::id).containsExactly(1L, 2L);
    }

    @Test
    void findBestMatchCandidate_forMarketBuy_usesLowestLimitSellAndIgnoresMarketSell() {
        insertOrder(100L, 10L, "STOCK001", "BUY", "MARKET", "PENDING", null, 10L, 0L, 0);
        insertOrder(1L, 10L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK001", "SELL", "MARKET", "PENDING", null, 10L, 0L, 2);
        insertOrder(3L, 30L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 3);
        insertOrder(4L, 40L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 4);

        OrderBookMatchCandidate candidate = reader.findBestMatchCandidate("STOCK001", 10).orElseThrow();

        assertThat(candidate).isEqualTo(new OrderBookMatchCandidate(100L, 10L, 4L, 40L));
    }

    @Test
    void findBestMatchCandidate_forLimitBuy_prioritizesMarketSellThenCrossableLowestLimit() {
        insertOrder(100L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 0);
        insertOrder(1L, 20L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 1);
        insertOrder(2L, 30L, "STOCK001", "SELL", "MARKET", "PENDING", null, 10L, 0L, 2);
        insertOrder(3L, 40L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70100.00"), 10L, 0L, 3);

        OrderBookMatchCandidate candidate = reader.findBestMatchCandidate("STOCK001", 10).orElseThrow();

        assertThat(candidate).isEqualTo(new OrderBookMatchCandidate(100L, 10L, 2L, 30L));
    }

    @Test
    void accountAndHoldingQueries_readCurrentCashAndHoldingRows() {
        jdbcTemplate.update("insert into stock_account(id, cash_balance) values (10, 5000.00)");
        jdbcTemplate.update("""
                insert into stock_holding(id, account_id, symbol, quantity, reserved_quantity, average_price)
                values (1, 10, 'STOCK001', 30, 5, 65000.00)
                """);

        boolean hasEnoughCash = reader.hasEnoughCash(10L, new BigDecimal("4000.00"));
        OrderBookHoldingRow holding = reader.findHoldingForUpdate(10L, "STOCK001");

        assertThat(hasEnoughCash).isTrue();
        assertThat(holding.quantity()).isEqualTo(30L);
        assertThat(holding.reservedQuantity()).isEqualTo(5L);
        assertThat(holding.averagePrice()).isEqualByComparingTo(new BigDecimal("65000.00"));
    }

    private void insertOrder(
            long id,
            long accountId,
            String symbol,
            String side,
            String orderType,
            String status,
            BigDecimal limitPrice,
            long quantity,
            long filledQuantity,
            int createdAtSecond
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, account_id, symbol, side, order_type, market_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price, reserved_cash, created_at
                )
                values (?, ?, ?, ?, ?, 'ORDER_BOOK', ?, ?, ?, ?, null, 0.00, ?)
                """,
                id,
                accountId,
                symbol,
                side,
                orderType,
                status,
                limitPrice,
                quantity,
                filledQuantity,
                LocalDateTime.of(2026, 6, 29, 9, 0).plusSeconds(createdAtSecond)
        );
    }

}
