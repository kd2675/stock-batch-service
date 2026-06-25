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
class AutoParticipantCashFlowServiceTest {

    @Autowired
    private AutoParticipantCashFlowService autoParticipantCashFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_account_cash_flow");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_market_config");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("delete from stock_order_book_instrument");
        jdbcTemplate.update("delete from stock_auto_participant_profile_config");
        jdbcTemplate.update("delete from stock_auto_participant");
    }

    @Test
    void fundRecurringCash_withoutEnabledMarketConfig_paysActiveParticipant() {
        insertAutoParticipant("stock-auto-payday", "PAYDAY_ACCUMULATOR", true, null, null, null);
        insertActiveAccount("stock-auto-payday", "0.00");
        insertDisabledMarketConfigAndSymbolStrategy("stock-auto-payday");

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isEqualTo(1);
        assertThat(queryDecimal("""
                select amount
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-payday'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isEqualByComparingTo(new BigDecimal("300000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-payday'"))
                .isEqualByComparingTo(new BigDecimal("300000.00"));
    }

    @Test
    void fundRecurringCash_participantRecurringCashOverridesProfileRecurringDeposit() {
        insertAutoParticipant("stock-auto-custom", "PAYDAY_ACCUMULATOR", true, "50000.00", "0.5", "HOUR");
        insertActiveAccount("stock-auto-custom", "0.00");

        autoParticipantCashFlowService.fundRecurringCash();
        autoParticipantCashFlowService.fundRecurringCash();

        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-custom'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(1L);
        assertThat(queryDecimal("""
                select amount
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-custom'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-custom'
                  and f.reason = 'AUTO_PROFILE_RECURRING_DEPOSIT'
                """)).isZero();
    }

    @Test
    void fundRecurringCash_disabledParticipantAndClosedAccount_areNotPaid() {
        insertAutoParticipant("stock-auto-disabled", "PAYDAY_ACCUMULATOR", false, null, null, null);
        insertActiveAccount("stock-auto-disabled", "0.00");
        insertAutoParticipant("stock-auto-closed", "PAYDAY_ACCUMULATOR", true, null, null, null);
        insertClosedAccount("stock-auto-closed", "0.00");

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isZero();
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
    }

    @Test
    void fundRecurringCash_depositsAgainAfterConfiguredInterval() {
        insertAutoParticipant("stock-auto-second", "NOISE_TRADER", true, "10000.00", "1.0", "SECOND");
        insertActiveAccount("stock-auto-second", "0.00");
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 10000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', DATEADD('SECOND', -2, CURRENT_TIMESTAMP)
                from stock_account
                where user_key = 'stock-auto-second'
                """);

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isEqualTo(1);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-second'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(2L);
    }

    private void insertDisabledMarketConfigAndSymbolStrategy(String userKey) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, enabled, created_at, updated_at)
                values ('005930', '삼성전자 주문장', 'ORDERBOOK', 70000.00, 300, 300, true, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                values ('005930', true, 'CLOSED', current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_market_config(symbol, enabled, intensity, max_order_quantity, order_ttl_seconds, updated_at)
                values ('005930', false, 5, 3, 15, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_symbol_config(user_key, symbol, enabled, intensity, updated_at)
                values (?, '005930', false, 5, current_timestamp)
                """,
                userKey
        );
    }

    private void insertAutoParticipant(
            String userKey,
            String profileType,
            boolean enabled,
            String recurringCashAmount,
            String recurringCashIntervalValue,
            String recurringCashIntervalUnit
    ) {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type,
                    recurring_cash_amount, recurring_cash_interval_value, recurring_cash_interval_unit,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                """,
                userKey,
                userKey,
                enabled,
                profileType,
                recurringCashAmount == null ? null : new BigDecimal(recurringCashAmount),
                recurringCashIntervalValue == null ? null : new BigDecimal(recurringCashIntervalValue),
                recurringCashIntervalUnit
        );
    }

    private void insertActiveAccount(String userKey, String cashBalance) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, status, created_at, updated_at)
                values (?, ?, 'ACTIVE', current_timestamp, current_timestamp)
                """,
                userKey,
                new BigDecimal(cashBalance)
        );
    }

    private void insertClosedAccount(String userKey, String cashBalance) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, status, created_at, updated_at)
                values (?, ?, 'CLOSED', current_timestamp, current_timestamp)
                """,
                userKey,
                new BigDecimal(cashBalance)
        );
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
