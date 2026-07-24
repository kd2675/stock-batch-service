package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "stock.batch.auto-participant-cash-flow.account-chunk-size=2")
@ActiveProfiles("test")
class AutoParticipantCashFlowServiceTest {

    @Test
    void validateAccountChunkSize_aboveVolumeLimit_rejectsConfiguration() {
        assertThatThrownBy(() -> AutoParticipantCashFlowService.validateAccountChunkSize(1_001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 1000");
    }

    @Autowired
    private AutoParticipantCashFlowService autoParticipantCashFlowService;

    @Autowired
    private AutoParticipantCashFlowTransactionExecutor transactionExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_market_session_fence");
        jdbcTemplate.update("delete from stock_post_close_phase_attempt");
        jdbcTemplate.update("delete from stock_post_close_cycle_metric");
        jdbcTemplate.update("delete from stock_post_close_cycle");
        jdbcTemplate.update("delete from stock_market_business_state");
        jdbcTemplate.update("delete from stock_auto_participant_cash_flow_run");
        jdbcTemplate.update("delete from stock_account_cash_flow");
        jdbcTemplate.update("delete from stock_auto_participant_order_budget");
        jdbcTemplate.update("delete from stock_auto_participant_funding_budget");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config");
        jdbcTemplate.update("delete from stock_auto_market_config");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("update stock_virtual_market_config set market_status = 'CLOSED'");
        jdbcTemplate.update("delete from stock_order_book_instrument");
        jdbcTemplate.update("delete from stock_auto_participant_profile_config");
        jdbcTemplate.update("delete from stock_auto_participant");
        jdbcTemplate.update("delete from stock_simulation_clock");
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date,
                    raw_simulation_date, version, created_at, updated_at
                )
                values ('DEFAULT', date '2026-07-01', null, date '2026-07-01', 0,
                        timestamp '2026-07-01 00:00:00', timestamp '2026-07-01 00:00:00')
                """
        );
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
        insertProfileBehaviorModel("PAYDAY_ACCUMULATOR", "V1");
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
        assertThat(queryLong("select count(*) from stock_auto_participant_funding_budget")).isZero();
    }

    @Test
    void fundRecurringCash_v2ExecutePaydayParticipant_createsPurposeBudget() {
        insertProfileBehaviorModel("PAYDAY_ACCUMULATOR", "V2");
        insertAutoParticipant("stock-auto-v2-payday", "PAYDAY_ACCUMULATOR", true, "50000.00", "1", "DAY");
        insertActiveAccount("stock-auto-v2-payday", "0.00");

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isEqualTo(1);
        assertThat(queryDecimal("""
                select available_amount
                  from stock_auto_participant_funding_budget b
                  join stock_account a on a.id = b.account_id
                 where a.user_key = 'stock-auto-v2-payday'
                   and b.budget_type = 'PAYDAY'
                   and b.status = 'ACTIVE'
                """)).isEqualByComparingTo("50000.00");
    }

    @Test
    void fundRecurringCash_v2ExecuteNonPurposeProfile_doesNotCreatePurposeBudget() {
        insertProfileBehaviorModel("NOISE_TRADER", "V2");
        insertAutoParticipant("stock-auto-v2-noise", "NOISE_TRADER", true, "50000.00", "1", "DAY");
        insertActiveAccount("stock-auto-v2-noise", "0.00");

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isEqualTo(1);
        assertThat(queryLong("""
                select count(*)
                  from stock_auto_participant_funding_budget b
                  join stock_account a on a.id = b.account_id
                 where a.user_key = 'stock-auto-v2-noise'
                """)).isZero();
    }

    @Test
    void fundRecurringCash_sameRunKeyAfterCompletedBusinessCommits_doesNotPayAgain() {
        insertAutoParticipant("stock-auto-restart-complete", "NOISE_TRADER", true, "10000.00", "1.0", "SECOND");
        insertActiveAccount("stock-auto-restart-complete", "0.00");
        insertPausedSimulationClock();

        int firstProcessed = autoParticipantCashFlowService.fundRecurringCash("restart-complete-run");
        jdbcTemplate.update(
                """
                update stock_account_cash_flow
                   set created_at = timestamp '2026-06-30 23:59:58'
                 where account_id = (
                       select id from stock_account where user_key = 'stock-auto-restart-complete'
                 )
                """
        );
        int restartedProcessed = autoParticipantCashFlowService.fundRecurringCash("restart-complete-run");

        assertThat(firstProcessed + ":" + restartedProcessed).isEqualTo("1:1");
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isEqualTo(1L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-restart-complete'"))
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(queryLong("select processed_count from stock_auto_participant_cash_flow_run where run_key = 'restart-complete-run'"))
                .isEqualTo(1L);

        int nextRunProcessed = autoParticipantCashFlowService.fundRecurringCash("restart-next-run");

        assertThat(nextRunProcessed).isEqualTo(1);
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isEqualTo(2L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-restart-complete'"))
                .isEqualByComparingTo(new BigDecimal("20000.00"));
    }

    @Test
    void fundRecurringCash_partialRunRestart_resumesAfterCommittedAccountCursor() {
        insertAutoParticipant("stock-auto-restart-1", "NOISE_TRADER", true, "10000.00", "1.0", "SECOND");
        insertActiveAccount("stock-auto-restart-1", "0.00");
        insertAutoParticipant("stock-auto-restart-2", "NOISE_TRADER", true, "10000.00", "1.0", "SECOND");
        insertActiveAccount("stock-auto-restart-2", "0.00");
        insertAutoParticipant("stock-auto-restart-3", "NOISE_TRADER", true, "10000.00", "1.0", "SECOND");
        insertActiveAccount("stock-auto-restart-3", "0.00");
        insertPausedSimulationClock();
        long firstAccountId = queryLong(
                "select id from stock_account where user_key = 'stock-auto-restart-1'"
        );
        jdbcTemplate.update(
                "update stock_account set cash_balance = 10000.00 where id = ?",
                firstAccountId
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    account_id, flow_type, amount, reason, created_by, created_at
                ) values (?, 'DEPOSIT', 10000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT',
                          'AUTO_MARKET', timestamp '2026-06-30 23:59:58')
                """,
                firstAccountId
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_cash_flow_run(
                    run_key, operation, last_account_id, processed_count,
                    completed_at, created_at, updated_at
                ) values ('restart-partial-run', 'SCHEDULED', ?, 1, null,
                          current_timestamp, current_timestamp)
                """,
                firstAccountId
        );

        int totalProcessed = autoParticipantCashFlowService.fundRecurringCash("restart-partial-run");

        assertThat(totalProcessed).isEqualTo(3);
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isEqualTo(3L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-restart-1'"))
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-restart-2'"))
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-restart-3'"))
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(queryLong("select processed_count from stock_auto_participant_cash_flow_run where run_key = 'restart-partial-run'"))
                .isEqualTo(3L);
        assertThat(queryLong("select count(*) from stock_auto_participant_cash_flow_run where completed_at is not null"))
                .isEqualTo(1L);
    }

    @Test
    void recurringCashChunk_businessFailureRollsBackCashFlowAndRunCursorTogether() {
        insertActiveAccount("stock-auto-rollback", "0.00");
        long accountId = queryLong("select id from stock_account where user_key = 'stock-auto-rollback'");
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_cash_flow_run(
                    run_key, operation, last_account_id, processed_count,
                    completed_at, created_at, updated_at
                ) values ('rollback-run', 'SCHEDULED', 0, 0, null,
                          current_timestamp, current_timestamp)
                """
        );

        assertThatThrownBy(() -> transactionExecutor.execute(() -> {
            jdbcTemplate.update(
                    "update stock_account set cash_balance = 10000.00 where id = ?",
                    accountId
            );
            jdbcTemplate.update(
                    """
                    insert into stock_account_cash_flow(
                        account_id, flow_type, amount, reason, created_by, created_at
                    ) values (?, 'DEPOSIT', 10000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT',
                              'AUTO_MARKET', timestamp '2026-07-01 00:00:00')
                    """,
                    accountId
            );
            jdbcTemplate.update(
                    """
                    update stock_auto_participant_cash_flow_run
                       set last_account_id = ?, processed_count = 1
                     where run_key = 'rollback-run'
                    """,
                    accountId
            );
            throw new IllegalStateException("simulated chunk failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated chunk failure");
        assertThat(queryDecimal("select cash_balance from stock_account where id = " + accountId))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
        assertThat(queryLong("select last_account_id from stock_auto_participant_cash_flow_run where run_key = 'rollback-run'"))
                .isZero();
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
    void fundRecurringCash_regularSession_doesNotDeposit() {
        insertAutoParticipant("stock-auto-regular-auto", "PAYDAY_ACCUMULATOR", true, "50000.00", "1.0", "DAY");
        insertActiveAccount("stock-auto-regular-auto", "0.00");
        insertSimulationClockAt(LocalDateTime.of(2026, 7, 1, 9, 0));

        int funded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(funded).isZero();
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-regular-auto'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fundRecurringCashManually_regularSession_doesNotDeposit() {
        insertAutoParticipant("stock-auto-regular-manual", "PAYDAY_ACCUMULATOR", true, "50000.00", "1.0", "DAY");
        insertActiveAccount("stock-auto-regular-manual", "0.00");
        insertSimulationClockAt(LocalDateTime.of(2026, 7, 1, 9, 0));

        int funded = autoParticipantCashFlowService.fundRecurringCashManually();

        assertThat(funded).isZero();
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-regular-manual'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fundRecurringCash_databaseMarketStillOpen_rejectsBeforeCandidateQueries() {
        insertPausedSimulationClock();
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                values ('CASH-GATE', true, 'OPEN', current_timestamp)
                """
        );

        assertThatThrownBy(autoParticipantCashFlowService::fundRecurringCash)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("while any market is open");
    }

    @Test
    void fundRecurringCash_closeFreezeInProgress_rejectsBeforeAccountUpdate() {
        insertAutoParticipant("stock-auto-freeze", "PAYDAY_ACCUMULATOR", true, "50000.00", "1.0", "DAY");
        insertActiveAccount("stock-auto-freeze", "0.00");
        insertPausedSimulationClock();
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    business_date, scope_type, scope_key, phase, status, created_at, updated_at
                )
                values (date '2026-07-01', 'FULL_MARKET', 'ALL',
                        'CLOSE_REQUESTED', 'RUNNING', current_timestamp, current_timestamp)
                """
        );

        assertThatThrownBy(autoParticipantCashFlowService::fundRecurringCashManually)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger freeze is in progress");
        assertThat(queryLong("select count(*) from stock_account_cash_flow")).isZero();
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
    void fundRecurringCashManually_doesNotDepositWhenConfiguredIntervalHasNotElapsed() {
        insertAutoParticipant("stock-auto-manual", "PAYDAY_ACCUMULATOR", true, "50000.00", "2.0", "HOUR");
        insertActiveAccount("stock-auto-manual", "0.00");
        jdbcTemplate.update("""
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 50000.00, 'AUTO_PARTICIPANT_RECURRING_DEPOSIT', 'AUTO_MARKET', current_timestamp
                from stock_account
                where user_key = 'stock-auto-manual'
                """);

        int funded = autoParticipantCashFlowService.fundRecurringCashManually();

        assertThat(funded).isZero();
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-manual'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                """)).isEqualTo(1L);
        assertThat(queryLong("""
                select count(*)
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'stock-auto-manual'
                  and f.reason = 'AUTO_PARTICIPANT_RECURRING_DEPOSIT'
                  and f.created_by = 'AUTO_MARKET_MANUAL'
                """)).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-manual'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fundRecurringCash_manualDepositDelaysNextAutomaticDeposit() {
        insertAutoParticipant("stock-auto-manual-then-auto", "PAYDAY_ACCUMULATOR", true, "50000.00", "2.0", "HOUR");
        insertActiveAccount("stock-auto-manual-then-auto", "0.00");

        int manualFunded = autoParticipantCashFlowService.fundRecurringCashManually();
        int automaticFunded = autoParticipantCashFlowService.fundRecurringCash();

        assertThat(manualFunded).isEqualTo(1);
        assertThat(automaticFunded).isZero();
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
                """)).isZero();
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'stock-auto-manual-then-auto'"))
                .isEqualByComparingTo(new BigDecimal("50000.00"));
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
                insert into stock_auto_market_config(symbol, enabled, max_order_quantity, order_ttl_seconds, updated_at)
                values ('005930', false, 3, 15, current_timestamp)
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

    private void insertProfileBehaviorModel(String profileType, String modelVersion) {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, behavior_model_version,
                    order_multiplier, aggression_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days, updated_at
                ) values (?, ?, 1.00, 1.00, 1.00, 0.50, 0.50, 0.00, 30, current_timestamp)
                """,
                profileType,
                modelVersion
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

    private void insertSimulationClockAt(LocalDateTime simulationDateTime) {
        LocalDateTime baseDateTime = simulationDateTime.toLocalDate().atStartOfDay();
        long accumulatedSeconds = Duration.between(baseDateTime, simulationDateTime).toSeconds();
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
                values ('DEFAULT', ?, 86400, ?, false, null, ?, 'Asia/Seoul', current_timestamp, current_timestamp)
                """,
                simulationDateTime.toLocalDate(),
                accumulatedSeconds,
                simulationDateTime
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
