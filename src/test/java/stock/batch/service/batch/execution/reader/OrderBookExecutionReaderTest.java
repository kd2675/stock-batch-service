package stock.batch.service.batch.execution.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
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
                    created_at timestamp not null
                )
                """);
        reader = new OrderBookExecutionReader(jdbcTemplate);
    }

    @Test
    void findExecutableSymbols_returnsOpenOrderBookSymbolsOnlyForOpenMarkets() {
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK001', true, 'OPEN')");
        jdbcTemplate.update("insert into stock_order_book_market_config(symbol, enabled, market_status) values ('STOCK002', true, 'CLOSED')");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK001', true)");
        jdbcTemplate.update("insert into stock_order_book_instrument(symbol, enabled) values ('STOCK002', true)");
        insertOrder(1L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK002", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 2);
        insertOrder(3L, 30L, "STOCK001", "BUY", "LIMIT", "FILLED", new BigDecimal("70100.00"), 10L, 10L, 3);

        List<String> symbols = reader.findExecutableSymbols();

        assertThat(symbols).containsExactly("STOCK001");
    }

    @Test
    void findBestBuyCandidates_prioritizesMarketThenHigherLimitThenOlderOrder() {
        insertOrder(1L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70100.00"), 10L, 0L, 2);
        insertOrder(3L, 30L, "STOCK001", "BUY", "MARKET", "PENDING", null, 10L, 0L, 3);
        insertOrder(4L, 40L, "STOCK001", "BUY", "LIMIT", "FILLED", new BigDecimal("80000.00"), 10L, 10L, 4);

        List<OrderBookOrderRow> candidates = reader.findBestBuyCandidates("STOCK001", 3);

        assertThat(candidates)
                .extracting(OrderBookOrderRow::id)
                .containsExactly(3L, 2L, 1L);
    }

    @Test
    void findBestSell_forMarketBuy_usesLowestLimitSellAndIgnoresMarketSell() {
        OrderBookOrderRow marketBuy = orderRow(100L, 10L, "BUY", "MARKET", null);
        insertOrder(1L, 10L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69000.00"), 10L, 0L, 1);
        insertOrder(2L, 20L, "STOCK001", "SELL", "MARKET", "PENDING", null, 10L, 0L, 2);
        insertOrder(3L, 30L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, 3);
        insertOrder(4L, 40L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 4);

        OrderBookOrderRow sell = reader.findBestSell("STOCK001", marketBuy);

        assertThat(sell.id()).isEqualTo(4L);
    }

    @Test
    void findBestSell_forLimitBuy_prioritizesMarketSellThenCrossableLowestLimit() {
        OrderBookOrderRow limitBuy = orderRow(100L, 10L, "BUY", "LIMIT", new BigDecimal("70000.00"));
        insertOrder(1L, 20L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("69900.00"), 10L, 0L, 1);
        insertOrder(2L, 30L, "STOCK001", "SELL", "MARKET", "PENDING", null, 10L, 0L, 2);
        insertOrder(3L, 40L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70100.00"), 10L, 0L, 3);

        OrderBookOrderRow sell = reader.findBestSell("STOCK001", limitBuy);

        assertThat(sell.id()).isEqualTo(2L);
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

    private OrderBookOrderRow orderRow(long id, long accountId, String side, String orderType, BigDecimal limitPrice) {
        return new OrderBookOrderRow(
                id,
                accountId,
                "STOCK001",
                side,
                orderType,
                limitPrice,
                10L,
                0L,
                null,
                BigDecimal.ZERO
        );
    }
}
