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
        jdbcTemplate.update("delete from stock_simulation_clock");
    }

    @Test
    void fundRecurringCash_withoutDirectRecurringCashSetting_doesNotPayProfileDefaultCash() {
        insertAutoParticipant("stock-auto-payday", "PAYDAY_ACCUMULATOR", true, null, null, null);
        insertActiveAccount("stock-auto-payday", "0.00");
        insertDisabledMarketConfigAndSymbolStrategy("stock-auto-payday");

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isZero();
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-payday'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
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
    void fundRecurringCash_dividendReinvestorDoesNotReceiveRecurringCash() {
        insertAutoParticipant("stock-auto-dividend", "DIVIDEND_REINVESTOR", true, "50000.00", "0.5", "HOUR");
        insertActiveAccount("stock-auto-dividend", "0.00");

        int funded = autoParticipantCashFlowService.fundRecurringCashManually();

        assertThat(funded).isZero();
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-dividend'
                """)).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-dividend'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
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
                select id, 'DEPOSIT', 10000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', DATEADD('SECOND', -2, CURRENT_DATE)
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

    @Test
    void fundRecurringCash_dayUnitUsesOneSimulationDayWindow() {
        insertAutoParticipant("stock-auto-day", "PAYDAY_ACCUMULATOR", true, "50000.00", "1.0", "DAY");
        insertActiveAccount("stock-auto-day", "0.00");
        insertPausedSimulationClock();
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 50000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', timestamp '2026-06-30 00:00:01'
                from stock_account
                where user_key = 'stock-auto-day'
                """);

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isZero();
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-day'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(1L);
    }

    @Test
    void fundRecurringCash_dayUnitPaysAgainAfterOneSimulationDay() {
        insertAutoParticipant("stock-auto-real-day", "PAYDAY_ACCUMULATOR", true, "50000.00", "1.0", "DAY");
        insertActiveAccount("stock-auto-real-day", "0.00");
        insertPausedSimulationClock();
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 50000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', timestamp '2026-06-29 23:59:59'
                from stock_account
                where user_key = 'stock-auto-real-day'
                """);

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isEqualTo(1);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-real-day'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(2L);
    }

    @Test
    void fundRecurringCashManually_depositsEvenWhenConfiguredIntervalHasNotElapsed() {
        insertAutoParticipant("stock-auto-manual", "PAYDAY_ACCUMULATOR", true, "50000.00", "2.0", "HOUR");
        insertActiveAccount("stock-auto-manual", "0.00");
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 50000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', current_timestamp
                from stock_account
                where user_key = 'stock-auto-manual'
                """);

        int funded = autoParticipantCashFlowService.fundRecurringCashManually();

        assertThat(funded).isEqualTo(1);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-manual'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(2L);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-manual'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                  and f.created_by = 'AUTO_MARKET_MANUAL'
                """)).isEqualTo(1L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-manual'"))
                .isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void fundRecurringCash_manualDepositDoesNotDelayNextAutomaticDeposit() {
        insertAutoParticipant("stock-auto-manual-then-auto", "PAYDAY_ACCUMULATOR", true, "50000.00", "2.0", "HOUR");
        insertActiveAccount("stock-auto-manual-then-auto", "0.00");

        int manualFunded = autoParticipantCashFlowService.fundRecurringCashManually();
        int automaticFunded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(manualFunded).isEqualTo(1);
        assertThat(automaticFunded).isEqualTo(1);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-manual-then-auto'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                  and f.created_by = 'AUTO_MARKET_MANUAL'
                """)).isEqualTo(1L);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-manual-then-auto'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                  and f.created_by = 'AUTO_MARKET'
                """)).isEqualTo(1L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-manual-then-auto'"))
                .isEqualByComparingTo(new BigDecimal("100000.00"));
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

    private void insertPausedSimulationClock() {
        jdbcTemplate.update("""
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values ('DEFAULT', date '2026-07-01', 7200, 0, false, null, timestamp '2026-07-01 00:00:00', 'Asia/Seoul', current_timestamp, current_timestamp)
                """);
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }
}
