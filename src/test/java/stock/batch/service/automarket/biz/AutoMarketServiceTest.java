package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.batch.service.automarket.profile.AutoProfileBehaviorRegistry;
import stock.batch.service.automarket.profile.DividendReinvestorBehavior;
import stock.batch.service.automarket.profile.LongTermHolderBehavior;
import stock.batch.service.automarket.profile.PaydayAccumulatorBehavior;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AutoMarketServiceTest {

    @Autowired
    private AutoMarketService autoMarketService;

    @Autowired
    private AutoParticipantCashFlowService autoParticipantCashFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AutoMarketWriter autoMarketWriter;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_account_cash_flow");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_instrument_report_event");
        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_instrument");
        jdbcTemplate.update("delete from stock_order_book_instrument");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("delete from stock_listing_auto_account_config");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_market_config");
        jdbcTemplate.update("delete from stock_auto_participant_profile_config");
        jdbcTemplate.update("delete from stock_auto_participant");
        jdbcTemplate.update("delete from stock_simulation_clock");

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
    void runAutoMarketStep_closedAutoParticipantAccountDoesNotTradeOrDuplicateAccount() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DAY_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, status, created_at, updated_at)
                values ('stock-auto-001', 50000000.00, 'CLOSED', current_timestamp, current_timestamp)
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_account where user_key = 'stock-auto-001'"))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where a.user_key = 'stock-auto-001' and o.symbol = '005930'"))
                .isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-001'"))
                .isEqualByComparingTo(new BigDecimal("50000000.00"));
    }

    @Test
    void reserveBuyCash_closedAccount_returnsFalseWithoutDebiting() {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, status, created_at, updated_at)
                values ('closed-auto-buyer', 100000.00, 'CLOSED', current_timestamp, current_timestamp)
                """
        );
        Long accountId = queryLong("select id from stock_account where user_key = 'closed-auto-buyer'");

        boolean reserved = autoMarketWriter.reserveBuyCash(accountId, new BigDecimal("1000.00"), LocalDateTime.now());

        assertThat(reserved).isFalse();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'closed-auto-buyer'"))
                .isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    void reserveSellQuantity_closedAccount_returnsFalseWithoutReserving() {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, status, created_at, updated_at)
                values ('closed-auto-seller', 0.00, 'CLOSED', current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'closed-auto-seller'
                """
        );
        Long accountId = queryLong("select id from stock_account where user_key = 'closed-auto-seller'");

        boolean reserved = autoMarketWriter.reserveSellQuantity(accountId, "005930", 3, LocalDateTime.now());

        assertThat(reserved).isFalse();
        assertThat(queryLong("select reserved_quantity from stock_holding where account_id = " + accountId + " and symbol = '005930'"))
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
    void runAutoMarketStep_listingOrdersExpireWithSimulationScaledTtl() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_participant");
        insertListingAccount("SELL_ONLY", "0.00", 100L, 7L);
        Long accountId = queryLong("select id from stock_account where user_key = 'stock-listing-005930'");
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, reserved_cash, created_at, updated_at
                )
                values ('listing-old-ttl-test', ?, '005930', 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING',
                        70000.00, 7, 0, 0.00, ?, ?)
                """,
                accountId,
                LocalDateTime.now().toLocalDate().atStartOfDay().minusSeconds(31),
                LocalDateTime.now().toLocalDate().atStartOfDay().minusSeconds(31)
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order where client_order_id = 'listing-old-ttl-test' and status = 'CANCELLED'"))
                .isEqualTo(1L);
        assertThat(queryLong("""
                select count(*)
                from stock_order o
                join stock_account a on a.id = o.account_id
                where a.user_key = 'stock-listing-005930'
                  and o.symbol = '005930'
                  and o.side = 'SELL'
                  and o.status = 'PENDING'
                """)).isEqualTo(1L);
        assertThat(queryLong("select reserved_quantity from stock_holding where account_id = " + accountId + " and symbol = '005930'"))
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
    void runAutoMarketStep_expiresShortLivedScalperOrdersBeforeLongTermOrders() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key = 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_market_config set order_ttl_seconds = 60, max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'SCALPER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LONG_TERM_HOLDER' where user_key = 'stock-auto-002'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                select 'ttl-scalper', id, '005930', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING',
                       70000.00, 1, 0, null, 70000.00, DATEADD('SECOND', -45, CURRENT_DATE), DATEADD('SECOND', -45, CURRENT_DATE)
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                select 'ttl-long-term', id, '005930', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING',
                       70000.00, 1, 0, null, 70000.00, DATEADD('SECOND', -45, CURRENT_DATE), DATEADD('SECOND', -45, CURRENT_DATE)
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryString("select status from stock_order where client_order_id = 'ttl-scalper'"))
                .isEqualTo("CANCELLED");
        assertThat(queryString("select status from stock_order where client_order_id = 'ttl-long-term'"))
                .isEqualTo("PENDING");
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

        assertThat(effectiveIntensity).isEqualTo(9);
    }

    @Test
    void runAutoMarketStep_newsReactiveBuysOnStrongPositiveReportEvenWhenBaseIntensityIsLow() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'NEWS_REACTIVE' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );
        insertReportScore(10);

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_newsReactiveSellsOnStrongNegativeReportEvenWhenBaseIntensityIsHigh() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'NEWS_REACTIVE' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );
        insertReportScore(1);

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isPositive();
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
    void buyBias_scalperTakesProfitMoreThanLongTermHolderOnWinningPosition() {
        double scalperWinningBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.SCALPER,
                5,
                0,
                0,
                0.10,
                5
        );
        double longTermWinningBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.LONG_TERM_HOLDER,
                5,
                0,
                0,
                0.10,
                5
        );

        assertThat(longTermWinningBias).isGreaterThan(scalperWinningBias + 0.10);
    }

    @Test
    void buyBias_overconfidentProfileKeepsBuyingBiasAfterWinningPosition() {
        double flatBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.OVERCONFIDENT,
                5,
                0,
                0,
                0,
                5
        );
        double winningBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.OVERCONFIDENT,
                5,
                0,
                0,
                0.20,
                5
        );

        assertThat(winningBias).isGreaterThan(flatBias + 0.08);
    }

    @Test
    void buyBias_paydayAccumulatorKeepsHigherBuyBiasThanNoiseTraderOnNeutralSignal() {
        double paydayBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.PAYDAY_ACCUMULATOR,
                5,
                0,
                0,
                0,
                5
        );
        double noiseBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.NOISE_TRADER,
                5,
                0,
                0,
                0,
                5
        );

        assertThat(paydayBias).isGreaterThan(noiseBias + 0.08);
    }

    @Test
    void paydayAccumulatorBehavior_canTakeProfitAfterAccumulatingWinningPosition() {
        PaydayAccumulatorBehavior behavior = new PaydayAccumulatorBehavior();
        AutoMarketConfig config = new AutoMarketConfig(
                "005930",
                5,
                3,
                15,
                300L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("30.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(1L, 5, AutoParticipantProfileType.PAYDAY_ACCUMULATOR);
        ProfileSignalContext firstOrder = new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                0,
                0,
                0.30,
                20L,
                new BigDecimal("1000000.00"),
                false,
                0,
                0
        );
        ProfileSignalContext followUpOrder = firstOrder.withOrderIndex(1);

        assertThat(behavior.chooseSide(firstOrder)).isEqualTo("BUY");
        assertThat(behavior.chooseSide(followUpOrder)).isEqualTo("SELL");
    }

    @Test
    void longTermHolderBehavior_doesNotKeepBuyingLargeWinningPosition() {
        LongTermHolderBehavior behavior = new LongTermHolderBehavior();
        AutoMarketConfig config = new AutoMarketConfig(
                "005930",
                5,
                3,
                15,
                300L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("30.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(1L, 5, AutoParticipantProfileType.LONG_TERM_HOLDER);
        ProfileSignalContext winningContext = new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                0,
                0,
                0.20,
                20L,
                new BigDecimal("1000000.00"),
                false,
                0,
                0
        );

        assertThat(behavior.chooseSide(winningContext)).isNull();
    }

    @Test
    void buyBias_dividendReinvestorHasNoRecurringCashByDefault() {
        DividendReinvestorBehavior behavior = new DividendReinvestorBehavior();

        assertThat(behavior.defaultPolicy().recurringDepositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void dividendReinvestorBehavior_recentDividendPaymentRaisesBuyBiasAndOrderCount() {
        DividendReinvestorBehavior behavior = new DividendReinvestorBehavior();
        AutoMarketConfig config = new AutoMarketConfig(
                "005930",
                5,
                3,
                15,
                300L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("30.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(1L, 5, AutoParticipantProfileType.DIVIDEND_REINVESTOR);
        ProfileSignalContext withoutDividend = new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                0,
                0,
                0.05,
                10L,
                new BigDecimal("100000.00"),
                BigDecimal.ZERO,
                false,
                0,
                0
        );
        ProfileSignalContext withDividend = new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                0,
                0,
                0.05,
                10L,
                new BigDecimal("100000.00"),
                new BigDecimal("10000.00"),
                false,
                0,
                0
        );

        assertThat(behavior.buyBias(withDividend)).isGreaterThan(behavior.buyBias(withoutDividend));
        assertThat(behavior.orderCount(withDividend)).isGreaterThan(behavior.orderCount(withoutDividend));
        assertThat(behavior.chooseSide(withDividend)).isEqualTo("BUY");
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
    void buyBias_fomoBuyerChasesRallyAndBuyCrowdMoreThanMomentumFollower() {
        double fomoBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.FOMO_BUYER,
                5,
                1.0,
                1.0,
                0,
                5
        );
        double momentumFollowerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.MOMENTUM_FOLLOWER,
                5,
                1.0,
                1.0,
                0,
                5
        );

        assertThat(fomoBias).isGreaterThan(momentumFollowerBias + 0.05);
    }

    @Test
    void buyBias_valueAnchorBuysMoreBelowReferenceAndLessAboveReference() {
        double belowReferenceBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.VALUE_ANCHOR,
                5,
                -1.0,
                0,
                0,
                5
        );
        double aboveReferenceBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.VALUE_ANCHOR,
                5,
                1.0,
                0,
                0,
                5
        );

        assertThat(belowReferenceBias).isGreaterThan(aboveReferenceBias + 0.20);
    }

    @Test
    void buyBias_swingTraderSitsBetweenMomentumAndContrarianOnRisingPrice() {
        double momentumFollowerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.MOMENTUM_FOLLOWER,
                5,
                1.0,
                0,
                0,
                5
        );
        double swingTraderBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.SWING_TRADER,
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

        assertThat(swingTraderBias).isBetween(contrarianBias, momentumFollowerBias);
    }

    @Test
    void buyBias_limitDownTrappedAvoidsSellingMoreThanPanicSellerWhenDeeplyLosing() {
        double trappedBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.LIMIT_DOWN_TRAPPED,
                5,
                -1.0,
                0,
                -0.35,
                10
        );
        double panicSellerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.PANIC_SELLER,
                5,
                -1.0,
                0,
                -0.35,
                10
        );

        assertThat(trappedBias).isGreaterThan(panicSellerBias + 0.35);
    }

    @Test
    void buyBias_averageDownBuyerBuysLosingPositionMoreThanDipBuyer() {
        double averageDownBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.AVERAGE_DOWN_BUYER,
                5,
                0,
                0,
                -0.10,
                10
        );
        double dipBuyerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.DIP_BUYER,
                5,
                0,
                0,
                -0.10,
                10
        );

        assertThat(averageDownBias).isGreaterThan(dipBuyerBias + 0.08);
    }

    @Test
    void buyBias_stopLossTraderSellsLosingPositionMoreThanLossAverseProfile() {
        double stopLossBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.STOP_LOSS_TRADER,
                5,
                -1.0,
                0,
                -0.12,
                10
        );
        double lossAverseBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.LOSS_AVERSE,
                5,
                -1.0,
                0,
                -0.12,
                10
        );

        assertThat(lossAverseBias).isGreaterThan(stopLossBias + 0.45);
    }

    @Test
    void buyBias_profitLockerSellsWinningPositionMoreThanScalper() {
        double profitLockerBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.PROFIT_LOCKER,
                5,
                0,
                0,
                0.15,
                10
        );
        double scalperBias = autoMarketService.buyBiasForProfile(
                AutoParticipantProfileType.SCALPER,
                5,
                0,
                0,
                0.15,
                10
        );

        assertThat(scalperBias).isGreaterThan(profitLockerBias + 0.05);
    }

    @Test
    void orderCount_cashDefensiveCanStayIdleWhenNoiseTraderStillTrades() {
        int cashDefensiveOrderCount = autoMarketService.orderCountForProfile(AutoParticipantProfileType.CASH_DEFENSIVE, 5, 0);
        int noiseTraderOrderCount = autoMarketService.orderCountForProfile(AutoParticipantProfileType.NOISE_TRADER, 5, 0);

        assertThat(cashDefensiveOrderCount).isEqualTo(0);
        assertThat(noiseTraderOrderCount).isPositive();
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
    void orderCount_observerAndLiquidityAvoidantCanStayIdleOnNeutralSignal() {
        assertThat(autoMarketService.orderCountForProfile(AutoParticipantProfileType.OBSERVER, 5, 0))
                .isZero();
        assertThat(autoMarketService.orderCountForProfile(AutoParticipantProfileType.LIQUIDITY_AVOIDANT, 5, 0))
                .isZero();
    }

    @Test
    void orderCount_longTermHolderTradesLessOftenThanDayTrader() {
        int longTermOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.LONG_TERM_HOLDER,
                5,
                0
        );
        int dayTraderOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.DAY_TRADER,
                5,
                0
        );

        assertThat(longTermOrderCount).isLessThan(dayTraderOrderCount);
    }

    @Test
    void orderCount_dayTraderTradesMoreOftenThanScalperOnNeutralSignal() {
        int dayTraderOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.DAY_TRADER,
                5,
                0
        );
        int scalperOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.SCALPER,
                5,
                0
        );

        assertThat(dayTraderOrderCount).isGreaterThanOrEqualTo(scalperOrderCount);
    }

    @Test
    void quantityUpperBound_whaleUsesLargerSizeThanSmallDiversifier() {
        int whaleUpperBound = autoMarketService.quantityUpperBoundForProfile(AutoParticipantProfileType.WHALE, 100);
        int smallDiversifierUpperBound = autoMarketService.quantityUpperBoundForProfile(AutoParticipantProfileType.SMALL_DIVERSIFIER, 100);

        assertThat(whaleUpperBound).isEqualTo(100);
        assertThat(smallDiversifierUpperBound).isLessThanOrEqualTo(45);
    }

    @Test
    void orderSizing_smallDiversifierUsesMoreOrdersButSmallerSizeThanWhale() {
        int smallDiversifierOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.SMALL_DIVERSIFIER,
                5,
                0
        );
        int whaleOrderCount = autoMarketService.orderCountForProfile(
                AutoParticipantProfileType.WHALE,
                5,
                0
        );
        int smallDiversifierQuantityUpperBound = autoMarketService.quantityUpperBoundForProfile(AutoParticipantProfileType.SMALL_DIVERSIFIER, 100);
        int whaleQuantityUpperBound = autoMarketService.quantityUpperBoundForProfile(AutoParticipantProfileType.WHALE, 100);

        assertThat(smallDiversifierOrderCount).isGreaterThan(whaleOrderCount);
        assertThat(smallDiversifierQuantityUpperBound).isLessThan(whaleQuantityUpperBound);
    }

    @Test
    void runAutoMarketStep_whaleAndSmallDiversifierUseDifferentRuntimeOrderSizes() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key = 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'WHALE' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'SMALL_DIVERSIFIER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 100 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select min(quantity) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isGreaterThanOrEqualTo(50L);
        assertThat(queryLong("select max(quantity) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'"))
                .isLessThanOrEqualTo(45L);
    }

    @Test
    void runAutoMarketStep_whaleBuyQuantityIsCappedByAffordableCash() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'WHALE' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 10 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "100000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
        assertThat(queryLong("select coalesce(max(quantity), 0) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isEqualTo(1L);
    }

    @Test
    void runAutoMarketStep_whaleSellQuantityIsCappedByAvailableHolding() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'WHALE' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 10 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-001', 0.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 2, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isPositive();
        assertThat(queryLong("select coalesce(max(quantity), 0) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isEqualTo(2L);
    }

    @Test
    void runAutoMarketStep_profileConfigOverridesOrderCountAndQuantity() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'NOISE_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 100 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                )
                values ('NOISE_TRADER', 2.00, 1.00, 1.00, 0.20, 0.10, 0.05, 0.20, 0.00, 30, current_timestamp)
                """
        );
        insertFundedAutoAccount("stock-auto-001", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isEqualTo(6L);
        assertThat(queryLong("select max(quantity) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isLessThanOrEqualTo(20L);
    }

    @Test
    void runAutoMarketStep_profileConfigOverridesProfitTaking() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'NOISE_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                )
                values ('NOISE_TRADER', 1.00, 1.00, 1.00, 1.00, 0.10, 0.05, 0.95, 0.00, 30, current_timestamp)
                """
        );
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 60000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_profileConfigWithMissingBehaviorWeightsKeepsProfileDefaultsWithoutForcingSingleSideOnly() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'MOMENTUM_FOLLOWER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 77000.00, previous_close = 70000.00 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                )
                values ('MOMENTUM_FOLLOWER', 1.00, 1.00, 1.00, 1.00, 0.10, 0.05, 0.20, 0.00, 30, current_timestamp)
                """
        );
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        long buyCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'");
        assertThat(buyCount).isPositive();
    }

    @Test
    void runAutoMarketStep_profileConfigOverridesCoreBehaviorWeights() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'NOISE_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 77000.00, previous_close = 70000.00 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type,
                    news_weight, momentum_weight, contrarian_weight, loss_aversion_weight, herding_weight,
                    market_making_weight, overconfidence_weight, noise_weight, panic_sell_weight, dip_buy_weight,
                    order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                )
                values (
                    'NOISE_TRADER',
                    0.00, 1.00, 0.00, 0.20, 0.00,
                    0.00, 0.00, 0.00, 0.00, 0.00,
                    1.00, 1.00, 1.00, 1.00,
                    0.10, 0.05, 0.20,
                    0.00, 30, current_timestamp
                )
                """
        );
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void orderTtl_scalperExpiresFasterThanLongTermHolder() {
        int scalperTtl = autoMarketService.orderTtlSecondsForProfile(AutoParticipantProfileType.SCALPER, 60);
        int longTermTtl = autoMarketService.orderTtlSecondsForProfile(AutoParticipantProfileType.LONG_TERM_HOLDER, 60);

        assertThat(scalperTtl).isLessThan(60);
        assertThat(longTermTtl).isGreaterThan(60);
    }

    @Test
    void runtimeOrderTtl_usesProjectTtlBecauseMarketTimestampsAreSimulationTime() {
        int scalperProjectTtl = autoMarketService.orderTtlSecondsForProfile(AutoParticipantProfileType.SCALPER, 60);
        int longTermProjectTtl = autoMarketService.orderTtlSecondsForProfile(AutoParticipantProfileType.LONG_TERM_HOLDER, 60);
        int scalperRuntimeTtl = autoMarketService.runtimeOrderTtlSecondsForProfile(AutoParticipantProfileType.SCALPER, 60);
        int longTermRuntimeTtl = autoMarketService.runtimeOrderTtlSecondsForProfile(AutoParticipantProfileType.LONG_TERM_HOLDER, 60);

        assertThat(scalperRuntimeTtl).isEqualTo(scalperProjectTtl);
        assertThat(longTermRuntimeTtl).isEqualTo(longTermProjectTtl);
        assertThat(scalperRuntimeTtl).isLessThan(longTermRuntimeTtl);
    }

    @Test
    void profileRegistry_coversEveryProfileTypeWithOwnDefaultPolicy() {
        AutoProfileBehaviorRegistry registry = AutoProfileBehaviorRegistry.createDefault();

        Arrays.stream(AutoParticipantProfileType.values()).forEach(profileType -> {
            assertThat(registry.behavior(profileType).type()).isEqualTo(profileType);
            assertThat(registry.defaultPolicies()).containsKey(profileType);
        });
    }

    @Test
    void profileRuntimeScale_doesNotChangeProjectPolicyValues() {
        int projectTtl = autoMarketService.orderTtlSecondsForProfile(AutoParticipantProfileType.DAY_TRADER, 60);
        int runtimeTtl = autoMarketService.runtimeOrderTtlSecondsForProfile(AutoParticipantProfileType.DAY_TRADER, 60);

        assertThat(projectTtl).isEqualTo(48);
        assertThat(runtimeTtl).isEqualTo(projectTtl);
    }

    @Test
    void profileRuntimeScale_isIndependentFromConfiguredRealDaySeconds() {
        insertSimulationClock(3600);

        int projectTtl = autoMarketService.orderTtlSecondsForProfile(AutoParticipantProfileType.DAY_TRADER, 60);
        int runtimeTtl = autoMarketService.runtimeOrderTtlSecondsForProfile(AutoParticipantProfileType.DAY_TRADER, 60);

        assertThat(projectTtl).isEqualTo(48);
        assertThat(runtimeTtl).isEqualTo(projectTtl);
    }

    @Test
    void priceMomentum_usesRecentProjectHourPriceTickBeforePreviousCloseFallback() {
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                values ('005930', 69000.00, 'test', ?, current_timestamp)
                """,
                LocalDateTime.of(2025, 12, 31, 22, 59, 50)
        );
        AutoMarketConfig config = new AutoMarketConfig(
                "005930",
                5,
                3,
                15,
                300,
                BigDecimal.valueOf(100),
                new BigDecimal("70000.00"),
                new BigDecimal("50000.00"),
                BigDecimal.valueOf(30),
                null
        );

        double momentum = autoMarketService.priceMomentum(config);

        assertThat(momentum).isGreaterThan(0.20);
        assertThat(momentum).isLessThan(0.50);
    }

    @Test
    void priceMomentum_isIndependentFromConfiguredRealDaySecondsForProjectHourWindow() {
        insertSimulationClock(3600);
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                values ('005930', 69000.00, 'test', ?, current_timestamp)
                """,
                LocalDateTime.of(2025, 12, 31, 22, 59, 50)
        );
        AutoMarketConfig config = new AutoMarketConfig(
                "005930",
                5,
                3,
                15,
                300,
                BigDecimal.valueOf(100),
                new BigDecimal("70000.00"),
                new BigDecimal("50000.00"),
                BigDecimal.valueOf(30),
                null
        );

        double momentum = autoMarketService.priceMomentum(config);

        assertThat(momentum).isGreaterThan(0.20);
        assertThat(momentum).isLessThan(0.50);
    }

    @Test
    void profilePolicies_allProfilesProduceValidActivitySignals() {
        Arrays.stream(AutoParticipantProfileType.values()).forEach(profileType -> {
            double buyBias = autoMarketService.buyBiasForProfile(profileType, 5, 0, 0, 0, 0);
            int orderCount = autoMarketService.orderCountForProfile(profileType, 5, 0);
            int quantityUpperBound = autoMarketService.quantityUpperBoundForProfile(profileType, 100);
            int orderTtlSeconds = autoMarketService.orderTtlSecondsForProfile(profileType, 60);

            assertThat(buyBias).as(profileType.name() + " buy bias").isBetween(0.08, 0.92);
            assertThat(orderCount).as(profileType.name() + " order count").isBetween(0, 8);
            assertThat(quantityUpperBound).as(profileType.name() + " quantity upper bound").isBetween(1, 100);
            assertThat(orderTtlSeconds).as(profileType.name() + " order TTL seconds").isBetween(1, 600);
        });
    }

    @Test
    void runAutoMarketStep_limitDownTrappedDeepLossAndNoCashDoesNotForceSell() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LIMIT_DOWN_TRAPPED' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-002', 0.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 100000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_lossAverseLosingPositionAndNoCashDoesNotForceSell() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LOSS_AVERSE' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-002', 0.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 80000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'SELL'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_limitDownTrappedAtLowerLimitAveragesDownWithoutSelling() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LIMIT_DOWN_TRAPPED' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 70000.00, previous_close = 100000.00 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-002', 500000.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 100000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'SELL'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_marketMakerPlacesTwoSidedQuotesWhenCashAndInventoryExist() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'MARKET_MAKER' where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-003' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-003", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-003'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003' and o.side = 'BUY'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003' and o.side = 'SELL'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_shortTermProfilesTakeProfitBeforeHighIntensityBuy() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key = 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'SCALPER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DAY_TRADER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 77000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key in ('stock-auto-001', 'stock-auto-002')
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key in ('stock-auto-001', 'stock-auto-002') and o.side = 'SELL'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_dayTraderTradesMoreOftenThanLongTermHolderAtStrongSignal() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key = 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LONG_TERM_HOLDER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DAY_TRADER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");

        autoMarketService.runAutoMarketStep();

        long longTermOrderCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'");
        long dayTraderOrderCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'");
        assertThat(dayTraderOrderCount).isGreaterThan(longTermOrderCount);
        assertThat(longTermOrderCount).isEqualTo(1L);
        assertThat(dayTraderOrderCount).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void runAutoMarketStep_overconfidentProfilePlacesMoreOrdersAfterLargeGain() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'OVERCONFIDENT' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 8 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 91000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 20, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isGreaterThanOrEqualTo(6L);
    }

    @Test
    void runAutoMarketStep_contrarianBuysAfterSharpDropEvenWhenIntensityIsLow() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'CONTRARIAN' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 65000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        long buyCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'");
        assertThat(buyCount).isPositive();
    }

    @Test
    void runAutoMarketStep_momentumFollowerBuysAfterSharpRiseEvenWhenIntensityIsLow() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'MOMENTUM_FOLLOWER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 77000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        long buyCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'");
        assertThat(buyCount).isPositive();
    }

    @Test
    void runAutoMarketStep_momentumFollowerSellsAfterSharpDropEvenWhenIntensityIsHigh() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'MOMENTUM_FOLLOWER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 65000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        long sellCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'");
        assertThat(sellCount).isPositive();
    }

    @Test
    void runAutoMarketStep_aggressiveBuyPriceDoesNotExceedDailyUpperLimit() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DAY_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 91000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
        assertThat(queryDecimal("select max(limit_price) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isLessThanOrEqualTo(new BigDecimal("91000.00"));
    }

    @Test
    void runAutoMarketStep_aggressiveSellPriceDoesNotFallBelowDailyLowerLimit() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DAY_TRADER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 49000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'SELL'"))
                .isPositive();
        assertThat(queryDecimal("select min(limit_price) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'SELL'"))
                .isGreaterThanOrEqualTo(new BigDecimal("49000.00"));
    }

    @Test
    void runAutoMarketStep_valueAnchorSellsAfterSharpRiseEvenWhenIntensityIsHigh() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'VALUE_ANCHOR' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 77000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        long sellCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'");
        assertThat(sellCount).isPositive();
    }

    @Test
    void runAutoMarketStep_valueAnchorBuysAfterSharpDropEvenWhenIntensityIsLow() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'VALUE_ANCHOR' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 65000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        long buyCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'");
        assertThat(buyCount).isPositive();
    }

    @Test
    void runAutoMarketStep_swingTraderTakesProfitAfterLargeUnrealizedGain() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'SWING_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 84000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_panicSellerSellsAfterSharpDropEvenWithCashAvailable() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'PANIC_SELLER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 65000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'SELL'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_herdFollowerBuysWhenOpenBuyOrdersDominate() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'HERD_FOLLOWER' where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-003' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-003", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-003'
                """
        );
        insertFundedAutoAccount("stock-crowd-buy", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                select 'crowd-buy-001', id, '005930', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING',
                       70000.00, 100, 0, null, 7000000.00, current_timestamp, current_timestamp
                from stock_account
                where user_key = 'stock-crowd-buy'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_herdFollowerSellsWhenOpenSellOrdersDominate() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'HERD_FOLLOWER' where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-003' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-003", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-003'
                """
        );
        insertFundedAutoAccount("stock-crowd-sell", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                select 'crowd-sell-001', id, '005930', 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING',
                       70000.00, 100, 0, null, 0.00, current_timestamp, current_timestamp
                from stock_account
                where user_key = 'stock-crowd-sell'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003' and o.side = 'SELL'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_dipBuyerBuysAfterSharpDropEvenWithExistingHoldings() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DIP_BUYER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 65000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        long buyCount = queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'");
        assertThat(buyCount).isPositive();
    }

    @Test
    void runAutoMarketStep_averageDownBuyerBuysLosingPosition() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'AVERAGE_DOWN_BUYER' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 56000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-002'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_profitLockerSellsWinningPosition() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'PROFIT_LOCKER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 84000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_patientProfilesStayIdleOnNeutralSignal() {
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LONG_TERM_HOLDER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LIQUIDITY_AVOIDANT' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'OBSERVER' where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");
        insertFundedAutoAccount("stock-auto-003", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key in ('stock-auto-001', 'stock-auto-002', 'stock-auto-003')
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key in ('stock-auto-001', 'stock-auto-002', 'stock-auto-003')"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_noiseTraderWithoutCashOrHoldingCannotCreateOrder() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'NOISE_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");

        autoMarketService.runAutoMarketStep();

        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-001'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_holding h join stock_account a on a.id = h.account_id where h.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_observerRespondsOnlyToStrongBuySignalWithSmallOrder() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'OBSERVER' where user_key = 'stock-auto-003'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-003' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 10 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-003", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003' and o.side = 'BUY'"))
                .isEqualTo(1L);
        assertThat(queryLong("select coalesce(sum(quantity), 0) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003'"))
                .isLessThanOrEqualTo(4L);
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-003' and o.side = 'SELL'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_liquidityAvoidantKeepsSmallOrderEvenOnStrongSignal() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LIQUIDITY_AVOIDANT' where user_key = 'stock-auto-002'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-002' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 10 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-002", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'BUY'"))
                .isBetween(1L, 2L);
        assertThat(queryLong("select coalesce(sum(quantity), 0) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002'"))
                .isLessThanOrEqualTo(12L);
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-002' and o.side = 'SELL'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_longTermHolderDeepLossAndNoCashDoesNotSell() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LONG_TERM_HOLDER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 1 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 65000.00, previous_close = 70000.00 where symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-001', 0.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 90000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isZero();
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where h.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isEqualTo(10L);
    }

    @Test
    void runAutoMarketStep_longTermHolderLargeGainDoesNotTakeProfitImmediately() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'LONG_TERM_HOLDER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 10 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 91000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isZero();
    }

    @Test
    void fundRecurringCash_paydayAccumulatorWithoutDirectRecurringCashSettingDoesNotDeposit() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'PAYDAY_ACCUMULATOR' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");

        insertAutoAccount("stock-auto-001", "0.00");

        autoParticipantCashFlowService.fundRecurringCash();
        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-001'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fundRecurringCash_participantRecurringCashOverridesProfileRecurringDeposit() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("""
                update stock_auto_participant
                set profile_type = 'PAYDAY_ACCUMULATOR',
                    recurring_cash_amount = 50000.00,
                    recurring_cash_interval_value = 0.5,
                    recurring_cash_interval_unit = 'HOUR'
                where user_key = 'stock-auto-001'
                """);
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");

        insertAutoAccount("stock-auto-001", "0.00");

        autoParticipantCashFlowService.fundRecurringCash();
        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(1L);
        assertThat(queryDecimal("""
                select amount
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isZero();
    }

    @Test
    void fundRecurringCash_participantRecurringCashDepositsAgainAfterSecondInterval() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("""
                update stock_auto_participant
                set profile_type = 'NOISE_TRADER',
                    recurring_cash_amount = 10000.00,
                    recurring_cash_interval_value = 1.0,
                    recurring_cash_interval_unit = 'SECOND'
                where user_key = 'stock-auto-001'
                """);
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 10000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', DATEADD('SECOND', -2, CURRENT_DATE)
                from stock_account
                where user_key = 'stock-auto-001'
                """);

        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(2L);
    }

    @Test
    void runAutoMarketStep_paydayAccumulatorDepositsAndBuysWhenNoHoldingExists() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("""
                update stock_auto_participant
                set profile_type = 'PAYDAY_ACCUMULATOR',
                    recurring_cash_amount = 300000.00,
                    recurring_cash_interval_value = 1.0,
                    recurring_cash_interval_unit = 'DAY'
                where user_key = 'stock-auto-001'
                """);
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertAutoAccount("stock-auto-001", "0.00");

        autoParticipantCashFlowService.fundRecurringCash();
        autoMarketService.runAutoMarketStep();

        assertThat(queryDecimal("""
                select cash_balance
                from stock_account
                where user_key = 'stock-auto-001'
                """)).isBetween(new BigDecimal("1.00"), new BigDecimal("299999.99"));
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isZero();
        assertThat(queryDecimal("select coalesce(sum(reserved_cash), 0) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_dividendReinvestorBuysAfterDividendPaymentWithoutRecurringCash() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'DIVIDEND_REINVESTOR' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        insertAutoAccount("stock-auto-001", "100000.00");
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 100000.00, 'DIVIDEND_PAYMENT', 'CORPORATE_ACTION', current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """);

        autoParticipantCashFlowService.fundRecurringCash();
        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isZero();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_fomoBuyerChasesSharpRiseWithBuyOrder() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'FOMO_BUYER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 91000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 91000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isPositive();
    }

    @Test
    void runAutoMarketStep_stopLossTraderSellsLosingPosition() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'STOP_LOSS_TRADER' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update("update stock_auto_market_config set max_order_quantity = 1 where symbol = '005930'");
        jdbcTemplate.update("update stock_price set current_price = 56000.00, previous_close = 70000.00 where symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, '005930', 10, 0, 70000.00, current_timestamp
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'SELL'"))
                .isPositive();
        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001' and o.side = 'BUY'"))
                .isZero();
    }

    @Test
    void runAutoMarketStep_cashDefensiveStaysIdleOnNeutralSignal() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'CASH_DEFENSIVE' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        insertFundedAutoAccount("stock-auto-001", "50000000.00");

        autoMarketService.runAutoMarketStep();

        assertThat(queryLong("select count(*) from stock_order o join stock_account a on a.id = o.account_id where o.symbol = '005930' and a.user_key = 'stock-auto-001'"))
                .isZero();
    }

    @Test
    void fundRecurringCash_profileConfigOverridesPaydayAccumulatorRecurringDepositAmount() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'PAYDAY_ACCUMULATOR' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                )
                values ('PAYDAY_ACCUMULATOR', 0.90, 0.80, 2.00, 0.70, 0.90, 0.55, 0.05, 120000.00, 30, current_timestamp)
                """
        );

        insertAutoAccount("stock-auto-001", "0.00");

        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryDecimal("""
                select amount
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isEqualByComparingTo(new BigDecimal("120000.00"));
    }

    @Test
    void fundRecurringCash_paydayAccumulatorDepositsOnlyAfterConfiguredInterval() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'PAYDAY_ACCUMULATOR' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                )
                values ('PAYDAY_ACCUMULATOR', 0.90, 0.80, 2.00, 0.70, 0.90, 0.55, 0.05, 120000.00, 30, current_timestamp)
                """
        );
        insertAutoAccount("stock-auto-001", "0.00");
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 120000.00, 'AUTO_PROFILE_RECURRING_DEPOSIT', 'AUTO_MARKET', DATEADD('DAY', -29, CURRENT_TIMESTAMP)
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isEqualTo(1L);

        jdbcTemplate.update("delete from stock_account_cash_flow where reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'");
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 120000.00, 'AUTO_PROFILE_RECURRING_DEPOSIT', 'AUTO_MARKET', DATEADD('DAY', -31, CURRENT_TIMESTAMP)
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isEqualTo(2L);
    }

    @Test
    void fundRecurringCash_profileConfigSupportsSecondRecurringDepositInterval() {
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key <> 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant set profile_type = 'PAYDAY_ACCUMULATOR' where user_key = 'stock-auto-001'");
        jdbcTemplate.update("update stock_auto_participant_symbol_config set intensity = 5 where user_key = 'stock-auto-001' and symbol = '005930'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier, order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days,
                    recurring_deposit_interval_value, recurring_deposit_interval_unit, updated_at
                )
                values ('PAYDAY_ACCUMULATOR', 0.90, 0.80, 2.00, 0.70, 0.90, 0.55, 0.05, 120000.00, 1, 1.0000, 'SECOND', current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values ('stock-auto-001', 0.00, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 120000.00, 'AUTO_PROFILE_RECURRING_DEPOSIT', 'AUTO_MARKET', DATEADD('SECOND', -10, CURRENT_DATE)
                from stock_account
                where user_key = 'stock-auto-001'
                """
        );

        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-001'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isEqualTo(2L);
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

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
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

    private void insertAutoAccount(String userKey, String amount) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values (?, ?, current_timestamp, current_timestamp)
                """,
                userKey,
                new BigDecimal(amount)
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

    private void insertReportScore(int score) {
        jdbcTemplate.update(
                """
                insert into stock_instrument_report_event(
                    symbol, event_type, title, summary, score, rise_reason, fall_reason, delete_reason, created_by, created_at
                )
                values ('005930', 'PUBLISH', '자동장 보고서', '자동장 테스트 보고서', ?, null, null, null, 'test', current_timestamp)
                """,
                score
        );
    }

    private void insertSimulationClock(int realSecondsPerSimulationDay) {
        jdbcTemplate.update("delete from stock_simulation_clock");
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at,
                    timezone, created_at, updated_at
                )
                values ('DEFAULT', DATE '2026-01-01', ?, 0, false, null, null, 'Asia/Seoul', current_timestamp, current_timestamp)
                """,
                realSecondsPerSimulationDay
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
