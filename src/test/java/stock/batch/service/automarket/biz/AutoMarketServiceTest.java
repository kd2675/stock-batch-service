package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AutoMarketServiceTest {

    @Autowired
    private AutoMarketService autoMarketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_instrument_report_event");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_instrument");
        jdbcTemplate.update("delete from stock_order_book_instrument");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("delete from stock_listing_auto_account_config");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_market_config");
        jdbcTemplate.update("delete from stock_auto_participant");

        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, enabled, created_at, updated_at)
                values ('005930', '삼성전자 주문장', 'ORDERBOOK', 70000.00, 300, 300, true, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values ('005930', 70000.00, 70000.00, current_timestamp, 'test')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                values ('005930', true, 'OPEN', current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(user_key, display_name, enabled, created_at, updated_at)
                values
                  ('stock-auto-001', '자동 참여자 1', true, current_timestamp, current_timestamp),
                  ('stock-auto-002', '자동 참여자 2', true, current_timestamp, current_timestamp),
                  ('stock-auto-003', '자동 참여자 3', true, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_market_config(symbol, enabled, intensity, max_order_quantity, order_ttl_seconds, updated_at)
                values ('005930', true, 5, 3, 15, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_symbol_config(user_key, symbol, enabled, intensity, updated_at)
                values
                  ('stock-auto-001', '005930', true, 10, current_timestamp),
                  ('stock-auto-002', '005930', true, 1, current_timestamp),
                  ('stock-auto-003', '005930', true, 5, current_timestamp)
                """
        );
    }

    @Test
    void runAutoMarketStep_enabledConfig_createsAutoAccountsWithoutInitialHoldings() {
        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_account where user_key like 'stock-auto-%'"))
                .isEqualTo(3L);
        assertThat(queryLong("select count(*) from stock_holding h join stock_account a on a.id = h.account_id where a.user_key like 'stock-auto-%' and symbol = '005930'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where a.user_key like 'stock-auto-%' and symbol = '005930'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_doesNotGrantIssuedSharesAsAutoHoldings() {
        jdbcTemplate.update("update stock_order_book_instrument set issued_shares = 150, tradable_shares = 150 where symbol = '005930'");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select coalesce(sum(quantity), 0) from stock_holding where symbol = '005930'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_listingSellOnlyAccountCreatesSmallSellOrderWithoutParticipants() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_participant");
        insertListingAccount("SELL_ONLY", "0.00", 100L, 0L);

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where a.user_key = 'stock-listing-005930' and o.symbol = '005930' and o.side = 'SELL'"))
                .isEqualTo(1L);
        assertThat(queryLong("select quantity from stock_order o join stock_account a on a.id = o.account_id where a.user_key = 'stock-listing-005930' and o.symbol = '005930'"))
                .isEqualTo(7L);
        assertThat(queryLong("select reserved_quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'stock-listing-005930' and h.symbol = '005930'"))
                .isEqualTo(7L);
    }

    @Test
    void runAutoMarketStep_listingBuyOnlyAccountCreatesBuyOrderOnlyWithinCash() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_participant");
        insertListingAccount("BUY_ONLY", "150000.00", 0L, 0L);

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where a.user_key = 'stock-listing-005930' and o.symbol = '005930' and o.side = 'BUY'"))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where a.user_key = 'stock-listing-005930' and o.symbol = '005930' and o.side = 'SELL'"))
                .isZero();
        assertThat(queryLong("select quantity from stock_order o join stock_account a on a.id = o.account_id where a.user_key = 'stock-listing-005930' and o.symbol = '005930'"))
                .isEqualTo(2L);
    }

    @Test
    void runAutoMarketStep_participantIntensityTen_createsBuyPressureForThatParticipant() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and side = 'BUY'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and side = 'SELL'"))
                .isZero();
        assertThat(queryDecimal("select min(limit_price) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isGreaterThanOrEqualTo(new BigDecimal("70000.00"));
    }

    @Test
    void effectiveIntensity_latestReportScoreIsBlendedWithParticipantStrategy() {
        int effectiveIntensity = autoMarketService.effectiveIntensity(
                new stock.batch.service.batch.automarket.model.AutoParticipantStrategy(1L, 5, AutoParticipantProfileType.NOISE_TRADER),
                new stock.batch.service.batch.automarket.model.AutoMarketConfig(
                        "005930",
                        5,
                        3,
                        15,
                        300,
                        BigDecimal.ONE,
                        new BigDecimal("70000.00"),
                        new BigDecimal("70000.00"),
                        10
                )
        );

        assertThat(effectiveIntensity).isEqualTo(7);
    }

    @Test
    void effectiveIntensity_newsReactiveProfile_respondsMoreStronglyToReportScore() {
        int effectiveIntensity = autoMarketService.effectiveIntensity(
                new stock.batch.service.batch.automarket.model.AutoParticipantStrategy(1L, 5, AutoParticipantProfileType.NEWS_REACTIVE),
                new stock.batch.service.batch.automarket.model.AutoMarketConfig(
                        "005930",
                        5,
                        3,
                        15,
                        300,
                        BigDecimal.ONE,
                        new BigDecimal("70000.00"),
                        new BigDecimal("70000.00"),
                        10
                )
        );

        assertThat(effectiveIntensity).isEqualTo(8);
    }

    @Test
    void buyBias_momentumFollowerAndContrarian_moveOppositeOnRisingPrice() {
        double momentumFollowerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.MOMENTUM_FOLLOWER,
                5,
                1.0,
                0,
                0,
                5
        );
        double contrarianBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.CONTRARIAN,
                5,
                1.0,
                0,
                0,
                5
        );

        assertThat(momentumFollowerBias).isGreaterThan(contrarianBias + 0.25);
    }

    @Test
    void buyBias_lossAverseProfile_prefersHoldingOrBuyingWhenPositionIsLosing() {
        double losingPositionBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.LOSS_AVERSE,
                5,
                0,
                0,
                -0.10,
                5
        );
        double winningPositionBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.LOSS_AVERSE,
                5,
                0,
                0,
                0.10,
                5
        );

        assertThat(losingPositionBias).isGreaterThan(winningPositionBias + 0.12);
    }

    @Test
    void buyBias_herdFollowerFollowsBuyCrowdAndMarketMakerLeansAgainstIt() {
        double herdFollowerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.HERD_FOLLOWER,
                5,
                0,
                1.0,
                0,
                5
        );
        double marketMakerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.MARKET_MAKER,
                5,
                0,
                1.0,
                0,
                5
        );

        assertThat(herdFollowerBias).isGreaterThan(marketMakerBias + 0.25);
    }

    @Test
    void buyBias_panicSellerAndDipBuyer_reactOppositeOnSharpDrop() {
        double panicSellerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.PANIC_SELLER,
                5,
                -1.0,
                0,
                0,
                5
        );
        double dipBuyerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.DIP_BUYER,
                5,
                -1.0,
                0,
                0,
                5
        );

        assertThat(dipBuyerBias).isGreaterThan(panicSellerBias + 0.40);
    }

    @Test
    void orderCount_overconfidentProfile_increasesAfterWinningPosition() {
        int flatOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.OVERCONFIDENT,
                8,
                0
        );
        int winningOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.OVERCONFIDENT,
                8,
                0.30
        );

        assertThat(winningOrderCount).isGreaterThan(flatOrderCount);
    }

    @Test
    void orderCount_observerTradesLessOftenThanScalper() {
        int observerOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.OBSERVER,
                5,
                0
        );
        int scalperOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.SCALPER,
                5,
                0
        );

        assertThat(observerOrderCount).isLessThan(scalperOrderCount);
    }

    @Test
    void runAutoMarketStep_participantIntensityOne_createsSellPressureForThatParticipant() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-002', 50000000.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 50000000.00, 'OPENING_GRANT', 'SYSTEM', current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and side = 'SELL'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and side = 'BUY'"))
                .isZero();
        assertThat(queryDecimal("select max(limit_price) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'"))
                .isLessThanOrEqualTo(new BigDecimal("70000.00"));
    }

    @Test
    void runAutoMarketStep_disabledParticipantSymbolConfig_skipsThatParticipantForSymbol() {
        jdbcTemplate.update("update stock_auto_participant_symbol_config set enabled = false where user_key = 'stock-auto-002' and symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'"))
                .isZero();
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private void insertFundedAutoAccount(String userKey, String amount) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values (?, ?, current_timestamp, current_timestamp)
                """,
                userKey,
                new BigDecimal(amount)
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', ?, 'OPENING_GRANT', 'SYSTEM', current_timestamp
                from stock_account
                where user_key = ?
                """,
                new BigDecimal(amount),
                userKey
        );
    }

    private void insertListingAccount(String positionSide, String cashBalance, long holdingQuantity, long reservedQuantity) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-listing-005930', ?, current_timestamp, current_timestamp)
                """,
                new BigDecimal(cashBalance)
        );
        if (holdingQuantity > 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                    select id, '005930', ?, ?, 70000.00, current_timestamp
                    from stock_account
                    where user_key = 'stock-listing-005930'
                    """,
                    holdingQuantity,
                    reservedQuantity
            );
        }
        jdbcTemplate.update(
                """
                insert into stock_listing_auto_account_config(
                    symbol, user_key, display_name, enabled, position_side,
                    max_order_quantity, order_ttl_seconds, price_offset_ticks, created_at, updated_at
                )
                values ('005930', 'stock-listing-005930', '삼성전자 상장주관사', true, ?, 7, 30, 0, current_timestamp, current_timestamp)
                """,
                positionSide
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
