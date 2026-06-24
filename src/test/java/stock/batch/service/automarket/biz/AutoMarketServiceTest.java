package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
                new stock.batch.service.batch.automarket.model.AutoParticipantStrategy(1L, 5),
                new stock.batch.service.batch.automarket.model.AutoMarketConfig(
                        "005930",
                        5,
                        3,
                        15,
                        300,
                        BigDecimal.ONE,
                        new BigDecimal("70000.00"),
                        10
                )
        );

        assertThat(effectiveIntensity).isEqualTo(7);
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

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
