package stock.batch.service.batch.automarket.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

class AutoMarketOrderReaderTest {

    private JdbcTemplate jdbcTemplate;
    private AutoMarketOrderReader reader;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("auto_market_order_reader_test")
        );
        jdbcTemplate.execute("""
                create table stock_account(
                    id bigint not null,
                    user_key varchar(64) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant(
                    user_key varchar(64) not null,
                    profile_type varchar(64) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order(
                    id bigint auto_increment primary key,
                    account_id bigint,
                    symbol varchar(20),
                    side varchar(10),
                    market_type varchar(20),
                    order_type varchar(20),
                    status varchar(30),
                    limit_price decimal(19, 2),
                    quantity bigint,
                    filled_quantity bigint,
                    reserved_cash decimal(19, 2),
                    created_at timestamp
                )
                """);
        reader = new AutoMarketOrderReader(jdbcTemplate);
    }

    @Test
    void findExpiredAutoOrders_locksCandidateOrderIdsThenReadsProfileAndCreatedAt() {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        LocalDateTime threshold = LocalDateTime.of(2026, 6, 29, 10, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 29, 9, 59);
        jdbcTemplate.update("insert into stock_account(id, user_key) values (10, 'auto-010')");
        jdbcTemplate.update("insert into stock_auto_participant(user_key, profile_type) values ('auto-010', 'SCALPER')");
        insertOrder(100L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 3L, 1L, new BigDecimal("140000.00"), createdAt);
        insertOrder(101L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 3L, 1L, new BigDecimal("140000.00"), threshold.plusSeconds(1));

        List<AutoOrder> orders = reader.findExpiredAutoOrders(config, threshold, 5400);

        assertThat(orders).hasSize(1);
        AutoOrder order = orders.getFirst();
        assertThat(order.id()).isEqualTo(100L);
        assertThat(order.profileType()).isEqualTo(AutoParticipantProfileType.SCALPER);
        assertThat(order.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void findExpiredListingAutoOrders_readsOrdersForListingAccountOnly() {
        ListingAutoAccountConfig config = new ListingAutoAccountConfig(
                "STOCK001",
                20L,
                "listing-020",
                "SELL",
                100,
                15,
                0,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("30.00")
        );
        LocalDateTime threshold = LocalDateTime.of(2026, 6, 29, 10, 0);
        insertOrder(200L, 20L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, BigDecimal.ZERO, threshold.minusMinutes(1));
        insertOrder(201L, 21L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, BigDecimal.ZERO, threshold.minusMinutes(2));
        insertOrder(202L, 20L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, BigDecimal.ZERO, threshold.plusMinutes(1));

        List<AutoOrder> orders = reader.findExpiredListingAutoOrders(config, threshold);

        assertThat(orders)
                .extracting(AutoOrder::id)
                .containsExactly(200L);
    }

    @Test
    void scalarOrderBookQueries_readBestPriceAndOpenQuantityWithJdbcClient() {
        jdbcTemplate.update(
                """
                insert into stock_order(account_id, symbol, side, market_type, order_type, status, limit_price, quantity, filled_quantity, reserved_cash, created_at)
                values
                  (10, 'STOCK001', 'BUY', 'ORDER_BOOK', 'LIMIT', 'PENDING', 70000.00, 10, 2, 0.00, timestamp '2026-06-29 09:00:00'),
                  (10, 'STOCK001', 'BUY', 'ORDER_BOOK', 'LIMIT', 'PENDING', 70100.00, 5, 0, 0.00, timestamp '2026-06-29 09:01:00'),
                  (20, 'STOCK001', 'SELL', 'ORDER_BOOK', 'LIMIT', 'PENDING', 70300.00, 8, 3, 0.00, timestamp '2026-06-29 09:02:00'),
                  (20, 'STOCK001', 'SELL', 'ORDER_BOOK', 'LIMIT', 'PENDING', 70200.00, 4, 1, 0.00, timestamp '2026-06-29 09:03:00'),
                  (20, 'STOCK001', 'SELL', 'ORDER_BOOK', 'LIMIT', 'FILLED', 69000.00, 100, 100, 0.00, timestamp '2026-06-29 09:04:00')
                """
        );

        assertThat(reader.findBestPrice("STOCK001", "BUY")).isEqualByComparingTo(new BigDecimal("70100.00"));
        assertThat(reader.findBestPrice("STOCK001", "SELL")).isEqualByComparingTo(new BigDecimal("70200.00"));
        assertThat(reader.findBestPrice("UNKNOWN", "BUY")).isNull();
        assertThat(reader.getOpenOrderQuantity("STOCK001", "BUY")).isEqualTo(13L);
        assertThat(reader.getOpenOrderQuantity(20L, "STOCK001", "SELL")).isEqualTo(8L);
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
            BigDecimal reservedCash,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, account_id, symbol, side, market_type, order_type, status,
                    limit_price, quantity, filled_quantity, reserved_cash, created_at
                )
                values (?, ?, ?, ?, 'ORDER_BOOK', ?, ?, ?, ?, ?, ?, ?)
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
                reservedCash,
                createdAt
        );
    }
}
