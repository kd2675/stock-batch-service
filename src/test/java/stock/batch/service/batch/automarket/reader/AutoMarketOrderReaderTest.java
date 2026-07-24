package stock.batch.service.batch.automarket.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
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
                    user_key varchar(64) not null,
                    status varchar(20) not null default 'ACTIVE'
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant(
                    user_key varchar(64) not null,
                    profile_type varchar(64) not null,
                    enabled boolean not null default true,
                    withdrawn_at timestamp null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant_profile_config(
                    profile_type varchar(64) not null primary key,
                    behavior_model_version varchar(20) not null default 'V2'
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
                    expires_at timestamp,
                    auto_profile_type varchar(64),
                    auto_behavior_model_version varchar(20),
                    created_at timestamp
                )
                """);
        reader = new AutoMarketOrderReader(jdbcTemplate);
    }

    @Test
    void findExpiredAutoOrders_readsEachCandidateAndItsPolicySnapshotInOneQuery() {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
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

        List<AutoOrder> orders = reader.findExpiredAutoOrders(
                config,
                Map.of(AutoParticipantProfileType.SCALPER, threshold),
                threshold,
                5400
        );

        assertThat(orders).hasSize(1);
        AutoOrder order = orders.getFirst();
        assertThat(order.id()).isEqualTo(100L);
        assertThat(order.profileType()).isEqualTo(AutoParticipantProfileType.SCALPER);
        assertThat(order.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void findExpiredAutoOrders_usesPolicySnapshotAndExactExpiryBoundary() {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001", 100, 15, 100000L,
                new BigDecimal("100.00"), new BigDecimal("70000.00"),
                new BigDecimal("70000.00"), null
        );
        LocalDateTime now = LocalDateTime.of(2026, 6, 29, 10, 0);
        jdbcTemplate.update("insert into stock_account(id, user_key) values (10, 'auto-010')");
        jdbcTemplate.update("insert into stock_auto_participant(user_key, profile_type) values ('auto-010', 'SCALPER')");
        insertOrder(110L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 3L, 0L, new BigDecimal("210000.00"), now.minusMinutes(5));
        jdbcTemplate.update(
                "update stock_order set expires_at = ?, auto_profile_type = 'MARKET_MAKER', auto_behavior_model_version = 'V2' where id = 110",
                now
        );

        List<AutoOrder> orders = reader.findExpiredAutoOrders(
                config,
                Map.of(AutoParticipantProfileType.MARKET_MAKER, now.minusHours(1)),
                now,
                10
        );

        assertThat(orders).singleElement().satisfies(order -> {
            assertThat(order.profileType()).isEqualTo(AutoParticipantProfileType.MARKET_MAKER);
            assertThat(order.behaviorModelVersion()).isEqualTo(AutoParticipantBehaviorModelVersion.V2);
            assertThat(order.expiresAt()).isEqualTo(now);
        });
    }

    @Test
    void findV2MarketMakerReplacementCandidates_readsOnlyOldOpenPinnedMarketMakerOrders() {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001", 100, 15, 100000L,
                new BigDecimal("100.00"), new BigDecimal("70000.00"),
                new BigDecimal("70000.00"), null
        );
        LocalDateTime createdBefore = LocalDateTime.of(2026, 6, 29, 10, 0);
        insertOrder(120L, 10L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("69900.00"),
                3L, 0L, new BigDecimal("209700.00"), createdBefore.minusSeconds(1));
        insertOrder(121L, 10L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70100.00"),
                3L, 0L, BigDecimal.ZERO, createdBefore);
        insertOrder(123L, 10L, "STOCK001", "SELL", "LIMIT", "PENDING", new BigDecimal("70200.00"),
                3L, 0L, BigDecimal.ZERO, createdBefore.minusSeconds(2));
        insertOrder(122L, 10L, "STOCK001", "BUY", "LIMIT", "CANCELLED", new BigDecimal("69800.00"),
                3L, 0L, BigDecimal.ZERO, createdBefore.minusMinutes(1));
        jdbcTemplate.update(
                "update stock_order set auto_profile_type = 'MARKET_MAKER', auto_behavior_model_version = 'V2', expires_at = ? where id in (120, 121, 122, 123)",
                createdBefore.plusMinutes(10)
        );

        List<AutoOrder> buyCandidates = reader.findV2MarketMakerReplacementCandidates(
                config, List.of(10L), createdBefore, "BUY", 10
        );
        List<AutoOrder> sellCandidates = reader.findV2MarketMakerReplacementCandidates(
                config, List.of(10L), createdBefore, "SELL", 10
        );

        assertThat(buyCandidates).extracting(AutoOrder::id).containsExactly(120L);
        assertThat(sellCandidates).extracting(AutoOrder::id).containsExactly(123L);
    }

    @Test
    void findExpiredAutoOrders_exactProfileThresholdPreventsLegacyHeadOfLineBlocking() {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001", 100, 15, 100000L,
                new BigDecimal("100.00"), new BigDecimal("70000.00"),
                new BigDecimal("70000.00"), null
        );
        LocalDateTime now = LocalDateTime.of(2026, 6, 29, 10, 0);
        jdbcTemplate.update("insert into stock_account(id, user_key) values (20, 'auto-long')");
        jdbcTemplate.update("insert into stock_account(id, user_key) values (21, 'auto-scalper')");
        jdbcTemplate.update("""
                insert into stock_auto_participant(user_key, profile_type) values
                    ('auto-long', 'LONG_TERM_HOLDER'),
                    ('auto-scalper', 'SCALPER')
                """);
        insertOrder(
                130L, 20L, "STOCK001", "BUY", "LIMIT", "PENDING",
                new BigDecimal("70000.00"), 3L, 0L, new BigDecimal("210000.00"),
                now.minusMinutes(2)
        );
        insertOrder(
                131L, 21L, "STOCK001", "BUY", "LIMIT", "PENDING",
                new BigDecimal("70000.00"), 3L, 0L, new BigDecimal("210000.00"),
                now.minusMinutes(1)
        );

        List<AutoOrder> orders = reader.findExpiredAutoOrders(
                config,
                Map.of(
                        AutoParticipantProfileType.LONG_TERM_HOLDER, now.minusMinutes(3),
                        AutoParticipantProfileType.SCALPER, now.minusSeconds(30)
                ),
                now,
                1
        );

        assertThat(orders).extracting(AutoOrder::id).containsExactly(131L);
    }

    @Test
    void findActiveV2MarketMakerAccountIds_appliesModelAtProfileScope() {
        jdbcTemplate.update("insert into stock_account(id, user_key) values (10, 'auto-v2')");
        jdbcTemplate.update("insert into stock_account(id, user_key) values (12, 'auto-other')");
        jdbcTemplate.update("""
                insert into stock_auto_participant_profile_config(profile_type, behavior_model_version)
                values ('MARKET_MAKER', 'V2')
                """);
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, profile_type
                ) values
                    ('auto-v2', 'MARKET_MAKER'),
                    ('auto-other', 'NOISE_TRADER')
                """);

        assertThat(reader.findActiveV2MarketMakerAccountIds(10)).containsExactly(10L);

        jdbcTemplate.update("""
                update stock_auto_participant_profile_config
                   set behavior_model_version = 'V1'
                 where profile_type = 'MARKET_MAKER'
                """);

        assertThat(reader.findActiveV2MarketMakerAccountIds(10)).isEmpty();
    }

    @Test
    void findExpiredListingAutoOrders_readsOrdersForListingAccountOnly() {
        ListingAutoAccountConfig config = new ListingAutoAccountConfig(
                "STOCK001",
                20L,
                "listing-020",
                "SELL_ONLY",
                "UNDERWRITER_RETURN",
                "RETURN_FIRST",
                1000L,
                new BigDecimal("70000.00"),
                100,
                15,
                0,
                8,
                3,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                0L,
                100L,
                0L,
                0L,
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
    void lockOpenOrdersForUpdate_revalidatesCandidatesAndReturnsPrimaryKeyOrder() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 29, 9, 0);
        insertOrder(300L, 30L, "STOCK001", "BUY", "LIMIT", "PENDING", new BigDecimal("70000.00"), 10L, 0L, new BigDecimal("700000.00"), createdAt);
        insertOrder(301L, 31L, "STOCK001", "SELL", "LIMIT", "FILLED", new BigDecimal("70000.00"), 10L, 10L, BigDecimal.ZERO, createdAt);
        List<AutoOrder> candidates = List.of(
                new AutoOrder(301L, 31L, "STOCK001", "SELL", 10L, 0L, BigDecimal.ZERO),
                new AutoOrder(300L, 30L, "STOCK001", "BUY", 10L, 0L, new BigDecimal("700000.00"))
        );

        List<AutoOrder> lockedOrders = reader.lockOpenOrdersForUpdate(candidates);

        assertThat(lockedOrders).extracting(AutoOrder::id).containsExactly(300L);
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
        assertThat(reader.findOrderBookSnapshot("STOCK001")).satisfies(snapshot -> {
            assertThat(snapshot.bestBid()).isEqualByComparingTo(new BigDecimal("70100.00"));
            assertThat(snapshot.bestAsk()).isEqualByComparingTo(new BigDecimal("70200.00"));
            assertThat(snapshot.openBuyQuantity()).isEqualTo(13L);
            assertThat(snapshot.openSellQuantity()).isEqualTo(8L);
        });
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
