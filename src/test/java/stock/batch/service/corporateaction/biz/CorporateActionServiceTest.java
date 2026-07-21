package stock.batch.service.corporateaction.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(properties = "stock.batch.corporate-action.account-chunk-size=2")
@ActiveProfiles("test")
class CorporateActionServiceTest {

    @Test
    void validateAccountChunkSize_aboveVolumeLimit_rejectsConfiguration() {
        assertThatThrownBy(() -> CorporateActionService.validateAccountChunkSize(1_001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 1000");
    }

    @Test
    void validateActionBatchLimit_aboveVolumeLimit_rejectsConfiguration() {
        assertThatThrownBy(() -> CorporateActionService.validateActionBatchLimit(201))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 200");
    }

    @Autowired
    private CorporateActionService corporateActionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_order where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_holding where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_holding_snapshot where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_order_book_daily_snapshot where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_account_cash_flow where account_id in (select id from stock_account where user_key like 'split-%' or user_key like 'dividend-%' or user_key like 'bonus-%' or user_key like 'rights-%' or user_key like 'capital-%')");
        jdbcTemplate.update("delete from stock_auto_participant where user_key like 'capital-%'");
        jdbcTemplate.update("delete from stock_corporate_action_processing");
        jdbcTemplate.update("delete from stock_account where user_key like 'split-%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'dividend-%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'bonus-%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'rights-%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'capital-%'");
        jdbcTemplate.update("delete from stock_corporate_action_entitlement where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_corporate_action where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_price_tick where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_price where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_listing_auto_account_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'stock-listing-zq%'");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_auto_participant_event_profile_config");
        jdbcTemplate.update("delete from stock_auto_market_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_order_book_market_config where symbol like 'ZQ%'");
        jdbcTemplate.update("update stock_order_book_market_config set market_status = 'CLOSED'");
        jdbcTemplate.update("update stock_virtual_market_config set market_status = 'CLOSED'");
        jdbcTemplate.update("update stock_market_session_fence set session_state = 'CLOSED'");
        jdbcTemplate.update("delete from stock_order_book_instrument where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_simulation_clock");
        setPausedSimulationClock(LocalDate.now(), LocalDateTime.now(), LocalTime.of(19, 0));
    }

    @Test
    void applyDueCorporateActions_pausedSimulationClock_usesSimulationClockForTimestamps() {
        LocalDateTime lastHeartbeatAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDate simulationDate = LocalDate.now();
        LocalDateTime expectedSimulationTime = simulationDate.atTime(19, 0);
        setPausedSimulationClock(simulationDate, lastHeartbeatAt, LocalTime.of(19, 0));
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ020", 100000L, 100000L);
        insertPrice("ZQ020", "70000.00");
        insertStockSplit("ZQ020", 1, 2, LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryDateTime("select listed_at from stock_corporate_action where symbol = 'ZQ020'"))
                .isEqualTo(expectedSimulationTime);
        assertThat(queryDateTime("select updated_at from stock_order_book_instrument where symbol = 'ZQ020'"))
                .isEqualTo(expectedSimulationTime);
    }

    @Test
    void applyDueCorporateActions_regularSession_waitsUntilAfterClose() {
        setPausedSimulationClock(LocalDate.now(), LocalDateTime.now(), LocalTime.of(10, 0));
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ021", 100000L, 100000L);
        insertPrice("ZQ021", "70000.00");
        insertStockSplit("ZQ021", 1, 2, LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ021'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ021'"))
                .isEqualTo(100000L);
    }

    @Test
    void applyDueCorporateActions_preOpenAppliesTodayExRightsUsingPreviousCloseSnapshot() {
        LocalDate simulationDate = LocalDate.now();
        LocalDate previousDate = simulationDate.minusDays(1);
        setPausedSimulationClock(previousDate, LocalDateTime.now(), simulationDate.atTime(5, 30));
        insertOrderBookInstrument("ZQ023", 100000L, 100000L);
        insertPrice("ZQ023", "70000.00");
        insertAccount("dividend-pre-open-holder");
        insertHolding("dividend-pre-open-holder", "ZQ023", 10L, 0L, "70000.00");
        long previousCloseRunId = insertHoldingSnapshot("ZQ023", previousDate, previousDate.atTime(18, 0));
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 99,
                       updated_at = ?
                 where symbol = 'ZQ023'
                """,
                LocalDateTime.now()
        );
        insertCashDividend(
                "ZQ023",
                "1000.00",
                "70000.00",
                "70000.00",
                simulationDate,
                simulationDate.plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ023'"))
                .isEqualTo("EX_RIGHTS_APPLIED");
        assertThat(queryLong("select quantity from stock_corporate_action_entitlement where symbol = 'ZQ023'"))
                .isEqualTo(10L);
        assertThat(queryLong("select holding_snapshot_run_id from stock_corporate_action_entitlement where symbol = 'ZQ023'"))
                .isEqualTo(previousCloseRunId);
        assertThat(queryLong("select quantity from stock_holding where symbol = 'ZQ023'"))
                .isEqualTo(99L);
    }

    @Test
    void applyDueCorporateActions_exRightsEntitlementsExceedChunkSize_createsEveryFrozenHolderOnce() {
        LocalDate today = LocalDate.now();
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ046", 100000L, 100000L);
        insertPrice("ZQ046", "70000.00");
        for (int index = 1; index <= 3; index++) {
            String userKey = "dividend-entitlement-chunk-" + index;
            insertAccount(userKey);
            insertHolding(userKey, "ZQ046", 10L * index, 0L, "70000.00");
        }
        long closeRunId = insertHoldingSnapshot(
                "ZQ046",
                today.minusDays(1),
                today.minusDays(1).atTime(18, 0)
        );
        insertCashDividend(
                "ZQ046",
                "1000.00",
                "70000.00",
                "70000.00",
                today,
                today.plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        String outcome = processedCount
                + ":" + queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ046'")
                + ":" + queryLong("select count(distinct account_id) from stock_corporate_action_entitlement where symbol = 'ZQ046'")
                + ":" + queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ046' and holding_snapshot_run_id = " + closeRunId);
        assertThat(outcome).isEqualTo("1:3:3:3");
    }

    @Test
    void applyDueCorporateActions_preOpenWithoutPreviousCloseRun_waits() {
        LocalDate simulationDate = LocalDate.now();
        setPausedSimulationClock(simulationDate.minusDays(1), LocalDateTime.now(), simulationDate.atTime(5, 30));
        insertOrderBookInstrument("ZQ024", 100000L, 100000L);
        insertPrice("ZQ024", "70000.00");
        insertCashDividend(
                "ZQ024",
                "1000.00",
                "70000.00",
                "70000.00",
                simulationDate,
                simulationDate.plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ024'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ024'"))
                .isEqualTo(100000L);
    }

    @Test
    void processExRightsStep_missingFrozenSnapshot_failsPhaseClosed() {
        LocalDate preparingBusinessDate = LocalDate.now();
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ047", 100000L, 100000L);
        insertPrice("ZQ047", "70000.00");
        insertAccount("dividend-preopen-missing-holder");
        insertHolding("dividend-preopen-missing-holder", "ZQ047", 10L, 0L, "70000.00");
        insertCashDividend(
                "ZQ047",
                "1000.00",
                "70000.00",
                "70000.00",
                preparingBusinessDate,
                preparingBusinessDate.plusDays(1)
        );

        Throwable failure = catchThrowable(() -> {
            corporateActionService.processExRightsStep(preparingBusinessDate, preparingBusinessDate);
            corporateActionService.processCapitalIncreaseListingStep(preparingBusinessDate, preparingBusinessDate);
            corporateActionService.processFreeShareListingStep(preparingBusinessDate, preparingBusinessDate);
            corporateActionService.processStockSplitStep(preparingBusinessDate, preparingBusinessDate);
            corporateActionService.processDelistingStep(preparingBusinessDate, preparingBusinessDate);
            corporateActionService.validatePreOpenSecurityTransformsStep(
                    preparingBusinessDate,
                    preparingBusinessDate
            );
        });
        String failureOutcome = failure.getClass().getSimpleName()
                + ":" + failure.getMessage()
                + ":" + java.util.Arrays.stream(failure.getSuppressed())
                        .map(Throwable::getMessage)
                        .toList();

        assertThat(failureOutcome)
                .contains("IllegalStateException", "incomplete transforms");
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ047'"))
                .isEqualTo("ANNOUNCED");
    }

    @Test
    void applyDueCorporateActions_afterCloseWithoutCompletedMarketCloseRun_waits() {
        insertOrderBookInstrument("ZQ022", 100000L, 100000L);
        insertPrice("ZQ022", "70000.00");
        insertStockSplit("ZQ022", 1, 2, LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ022'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ022'"))
                .isEqualTo(100000L);
    }

    @Test
    void applyDueCorporateActions_afterCloseWithOnlySymbolCloseRun_waitsForFullClose() {
        insertCompletedSymbolMarketCloseForToday("ZQ039");
        insertOrderBookInstrument("ZQ039", 100000L, 100000L);
        insertPrice("ZQ039", "70000.00");
        insertStockSplit("ZQ039", 1, 2, LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        String outcome = processedCount
                + ":" + queryString("select status from stock_corporate_action where symbol = 'ZQ039'")
                + ":" + queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ039'");
        assertThat(outcome).isEqualTo("0:ANNOUNCED:100000");
    }

    @Test
    void applyDueCorporateActions_cashDividendOnPaymentDate_keepsPriceAndPaysEntitlements() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ007", 100000L, 100000L);
        insertPrice("ZQ007", "70000.00");
        insertAccount("dividend-holder");
        insertHolding("dividend-holder", "ZQ007", 10L, 0L, "70000.00");
        long closeRunId = insertHoldingSnapshot(
                "ZQ007",
                LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(3).atTime(18, 0)
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 99,
                       updated_at = ?
                 where symbol = 'ZQ007'
                """,
                LocalDateTime.now()
        );
        insertCashDividend(
                "ZQ007",
                "1000.00",
                "70000.00",
                "69000.00",
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(2);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ007'"))
                .isEqualTo("PAID");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ007'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryString("select provider from stock_price where symbol = 'ZQ007'"))
                .isEqualTo("test");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'dividend-holder'"))
                .isEqualByComparingTo(new BigDecimal("10010000.00"));
        assertThat(queryLong("select quantity from stock_corporate_action_entitlement where symbol = 'ZQ007'"))
                .isEqualTo(10L);
        assertThat(queryLong("select holding_snapshot_run_id from stock_corporate_action_entitlement where symbol = 'ZQ007'"))
                .isEqualTo(closeRunId);
        assertThat(queryDecimal("""
                select amount
                from stock_account_cash_flow f
                join stock_account a on a.id = f.account_id
                where a.user_key = 'dividend-holder'
                  and f.reason = 'DIVIDEND_PAYMENT'
                """)).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ007'"))
                .isEqualTo("PAID");
    }

    @Test
    void processCashDividendPaymentStep_cashDividendExceedsChunkSize_commitsAllAccountUnitsAndCompletesAction() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ040", 100000L, 100000L);
        long actionId = insertAppliedCashDividend("ZQ040", LocalDate.now());
        for (int index = 1; index <= 3; index++) {
            String userKey = "dividend-chunk-" + index;
            insertAccount(userKey);
            insertAnnouncedDividendEntitlement(
                    actionId,
                    accountIdFor(userKey),
                    "ZQ040",
                    "1000.00"
            );
        }

        int processedCount = corporateActionService.processCashDividendPaymentStep(LocalDate.now());
        corporateActionService.validateCorporateCashStep(LocalDate.now());

        String outcome = processedCount
                + ":" + queryLong("select count(*) from stock_corporate_action_entitlement where action_id = "
                + actionId + " and status = 'PAID'")
                + ":" + queryLong("select count(*) from stock_corporate_action_processing where action_id = "
                + actionId + " and action_phase = 'cash-dividend-payment' and status = 'COMPLETED'")
                + ":" + queryLong("select count(*) from stock_account_cash_flow where reason = 'DIVIDEND_PAYMENT' "
                + "and account_id in (select id from stock_account where user_key like 'dividend-chunk-%')");
        assertThat(outcome).isEqualTo("1:3:4:3");
    }

    @Test
    void processCashDividendPaymentStep_moreActionsThanBatchLimit_restartsWithoutDuplicatePayment() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ044", 100000L, 100000L);
        insertOrderBookInstrument("ZQ045", 100000L, 100000L);
        insertAccount("dividend-action-chunk-1");
        insertAccount("dividend-action-chunk-2");
        long firstActionId = insertAppliedCashDividend("ZQ044", LocalDate.now());
        long secondActionId = insertAppliedCashDividend("ZQ045", LocalDate.now());
        insertAnnouncedDividendEntitlement(
                firstActionId,
                accountIdFor("dividend-action-chunk-1"),
                "ZQ044",
                "1000.00"
        );
        insertAnnouncedDividendEntitlement(
                secondActionId,
                accountIdFor("dividend-action-chunk-2"),
                "ZQ045",
                "1000.00"
        );
        ReflectionTestUtils.setField(corporateActionService, "actionBatchLimit", 1);

        try {
            int firstProcessed = corporateActionService.processCashDividendPaymentStep(LocalDate.now());
            assertThatThrownBy(() -> corporateActionService.validateCorporateCashStep(LocalDate.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("count=1");
            int secondProcessed = corporateActionService.processCashDividendPaymentStep(LocalDate.now());
            int finalValidation = corporateActionService.validateCorporateCashStep(LocalDate.now());
            int idempotentProcessed = corporateActionService.processCashDividendPaymentStep(LocalDate.now());

            String outcome = firstProcessed + ":" + secondProcessed + ":" + finalValidation + ":"
                    + idempotentProcessed + ":" + queryLong("""
                    select count(*)
                      from stock_account_cash_flow
                     where reason = 'DIVIDEND_PAYMENT'
                       and account_id in (
                           select id from stock_account where user_key like 'dividend-action-chunk-%'
                       )
                    """);
            assertThat(outcome).isEqualTo("1:1:0:0:2");
        } finally {
            ReflectionTestUtils.setField(corporateActionService, "actionBatchLimit", 25);
        }
    }

    @Test
    void processCashDividendPaymentStep_openMarket_rejectsBeforeCorporateLedgerWork() {
        LocalDate today = LocalDate.now();
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ099", 100000L, 100000L);
        insertOrderBookMarketConfig("ZQ099");

        assertThatThrownBy(() -> corporateActionService.processCashDividendPaymentStep(today))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("while any market is open");
    }

    @Test
    void corporateActionTable_cashDividendWithoutDividendAmount_rejectsInvalidLedgerRow() {
        insertOrderBookInstrument("ZQ008", 100000L, 100000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, status, base_price, theoretical_ex_rights_price,
                  ex_rights_date, payment_date, description, created_at
                ) values (?, 'CASH_DIVIDEND', 'ANNOUNCED', ?, ?, ?, ?, 'test', ?)
                """,
                "ZQ008",
                new BigDecimal("70000.00"),
                new BigDecimal("69000.00"),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(3),
                LocalDateTime.now()
        ))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void applyDueCorporateActions_bonusIssueOnListingDate_adjustsPriceAndCreditsShareEntitlements() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ009", 100000L, 100000L);
        insertPrice("ZQ009", "70000.00");
        insertAccount("bonus-holder");
        insertHolding("bonus-holder", "ZQ009", 100L, 0L, "70000.00");
        insertHoldingSnapshot(
                "ZQ009",
                LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(3).atTime(18, 0)
        );
        insertBonusIssue(
                "ZQ009",
                10000L,
                "70000.00",
                "63636.36",
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(2);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ009'"))
                .isEqualTo("LISTED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ009'"))
                .isEqualByComparingTo(new BigDecimal("63636.00"));
        assertThat(queryString("select provider from stock_price where symbol = 'ZQ009'"))
                .isEqualTo("corporate-action-free-share");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ009'"))
                .isEqualTo(110000L);
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'bonus-holder' and symbol = 'ZQ009'"))
                .isEqualTo(110L);
        assertThat(queryDecimal("select average_price from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'bonus-holder' and symbol = 'ZQ009'"))
                .isEqualByComparingTo(new BigDecimal("63636.36"));
        assertThat(queryLong("select share_quantity from stock_corporate_action_entitlement where symbol = 'ZQ009'"))
                .isEqualTo(10L);
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ009'"))
                .isEqualTo("PAID");
    }

    @Test
    void applyDueCorporateActions_stockDividendOnListingDate_adjustsPriceAndCreditsShareEntitlements() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ010", 100000L, 100000L);
        insertPrice("ZQ010", "70000.00");
        insertAccount("dividend-stock-holder");
        insertHolding("dividend-stock-holder", "ZQ010", 100L, 0L, "70000.00");
        insertHoldingSnapshot(
                "ZQ010",
                LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(3).atTime(18, 0)
        );
        insertFreeShareDistribution(
                "ZQ010",
                "STOCK_DIVIDEND",
                10000L,
                "70000.00",
                "63636.36",
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(2);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ010'"))
                .isEqualTo("LISTED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ010'"))
                .isEqualByComparingTo(new BigDecimal("63636.00"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'dividend-stock-holder' and symbol = 'ZQ010'"))
                .isEqualTo(110L);
        assertThat(queryLong("select share_quantity from stock_corporate_action_entitlement where symbol = 'ZQ010'"))
                .isEqualTo(10L);
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ010'"))
                .isEqualTo("PAID");
    }

    @Test
    void applyDueCorporateActions_stockSplitOnEffectiveDate_adjustsSharesHoldingsAndPrice() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ004", 100000L, 100000L);
        insertPrice("ZQ004", "70000.00");
        insertAccount("split-holder");
        insertHolding("split-holder", "ZQ004", 10L, 2L, "70000.00");
        insertStockSplit("ZQ004", 1, 5, LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ004'"))
                .isEqualTo("LISTED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ004'"))
                .isEqualTo(500000L);
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'split-holder' and symbol = 'ZQ004'"))
                .isEqualTo(50L);
        assertThat(queryLong("select reserved_quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'split-holder' and symbol = 'ZQ004'"))
                .isEqualTo(10L);
        assertThat(queryDecimal("select average_price from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'split-holder' and symbol = 'ZQ004'"))
                .isEqualByComparingTo(new BigDecimal("14000.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ004'"))
                .isEqualByComparingTo(new BigDecimal("14000.00"));
        assertThat(queryString("select provider from stock_price where symbol = 'ZQ004'"))
                .isEqualTo("corporate-action-split");
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = 'ZQ004' and provider = 'corporate-action-split'"))
                .isEqualTo(1L);
    }

    @Test
    void applyDueCorporateActions_stockSplitWithOpenOrder_waitsWithoutChangingShares() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ005", 100000L, 100000L);
        insertPrice("ZQ005", "70000.00");
        insertAccount("split-open-buyer");
        insertOpenOrder("split-open-order", "split-open-buyer", "ZQ005");
        insertStockSplit("ZQ005", 1, 5, LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ005'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ005'"))
                .isEqualTo(100000L);
    }

    @Test
    void applyDueCorporateActions_exRightsPaymentAndListing_withoutSubscriptionDoesNotIssueShares() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ001", 100000L, 100000L);
        insertPrice("ZQ001", "70000.00");
        insertHoldingSnapshot(
                "ZQ001",
                LocalDate.now().minusDays(5),
                LocalDate.now().minusDays(5).atTime(18, 0)
        );
        insertPaidInCapitalIncrease(
                "ZQ001",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(4),
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(3);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ001'"))
                .isEqualTo("LISTED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ001'"))
                .isEqualByComparingTo(new BigDecimal("63333.00"));
        assertThat(queryString("select provider from stock_price where symbol = 'ZQ001'"))
                .isEqualTo("corporate-action-rights");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ001'"))
                .isEqualTo(100000L);
        assertThat(queryLong("select tradable_shares from stock_order_book_instrument where symbol = 'ZQ001'"))
                .isEqualTo(100000L);
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = 'ZQ001' and provider = 'corporate-action-rights'"))
                .isEqualTo(1L);
    }

    @Test
    void applyDueCorporateActions_shareholderAllocationExRights_createsRightsEntitlement() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ025", 100000L, 100000L);
        insertPrice("ZQ025", "70000.00");
        insertAccount("rights-holder");
        insertHolding("rights-holder", "ZQ025", 100L, 0L, "70000.00");
        long closeRunId = insertHoldingSnapshot(
                "ZQ025",
                LocalDate.now().minusDays(1),
                LocalDate.now().minusDays(1).atTime(18, 0)
        );
        insertPaidInCapitalIncrease(
                "ZQ025",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now(),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(5)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ025'"))
                .isEqualTo("EX_RIGHTS_APPLIED");
        assertThat(queryLong("select quantity from stock_corporate_action_entitlement where symbol = 'ZQ025'"))
                .isEqualTo(100L);
        assertThat(queryLong("select share_quantity from stock_corporate_action_entitlement where symbol = 'ZQ025'"))
                .isEqualTo(50L);
        assertThat(queryDecimal("select cash_amount from stock_corporate_action_entitlement where symbol = 'ZQ025'"))
                .isEqualByComparingTo(new BigDecimal("2500000.00"));
        assertThat(queryLong("select holding_snapshot_run_id from stock_corporate_action_entitlement where symbol = 'ZQ025'"))
                .isEqualTo(closeRunId);
    }

    @Test
    void applyDueCorporateActions_shareholderAllocationExRights_usesLatestCumRightsClosePrice() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ044", 100000L, 100000L);
        insertPrice("ZQ044", "90000.00");
        insertAccount("rights-price-holder");
        insertHolding("rights-price-holder", "ZQ044", 100L, 0L, "90000.00");
        insertHoldingSnapshot(
                "ZQ044",
                LocalDate.now().minusDays(1),
                LocalDate.now().minusDays(1).atTime(18, 0)
        );
        jdbcTemplate.update(
                "update stock_price set current_price = 70000.00, previous_close = 70000.00 where symbol = 'ZQ044'"
        );
        insertPaidInCapitalIncrease(
                "ZQ044",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now(),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(5)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryDecimal("select base_price from stock_corporate_action where symbol = 'ZQ044'"))
                .isEqualByComparingTo(new BigDecimal("90000.00"));
        assertThat(queryDecimal("select theoretical_ex_rights_price from stock_corporate_action where symbol = 'ZQ044'"))
                .isEqualByComparingTo(new BigDecimal("76666.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ044'"))
                .isEqualByComparingTo(new BigDecimal("76666.00"));
    }

    @Test
    void applyDueCorporateActions_shareholderAllocationExRights_keepsCloseWhenIssuePriceIsNotLower() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ045", 100000L, 100000L);
        insertPrice("ZQ045", "40000.00");
        insertAccount("rights-high-issue-holder");
        insertHolding("rights-high-issue-holder", "ZQ045", 100L, 0L, "40000.00");
        insertHoldingSnapshot(
                "ZQ045",
                LocalDate.now().minusDays(1),
                LocalDate.now().minusDays(1).atTime(18, 0)
        );
        jdbcTemplate.update(
                "update stock_price set current_price = 35000.00, previous_close = 35000.00 where symbol = 'ZQ045'"
        );
        insertPaidInCapitalIncrease(
                "ZQ045",
                50000L,
                "50000.00",
                "35000.00",
                "40000.00",
                LocalDate.now(),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(5)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryDecimal("select base_price from stock_corporate_action where symbol = 'ZQ045'"))
                .isEqualByComparingTo(new BigDecimal("40000.00"));
        assertThat(queryDecimal("select theoretical_ex_rights_price from stock_corporate_action where symbol = 'ZQ045'"))
                .isEqualByComparingTo(new BigDecimal("40000.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ045'"))
                .isEqualByComparingTo(new BigDecimal("40000.00"));
    }

    @Test
    void applyDueCorporateActions_subscribedRightsListing_issuesSubscribedSharesAndCreditsHolding() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ026", 100000L, 100000L);
        insertPrice("ZQ026", "70000.00");
        insertAccount("capital-subscriber");
        insertHolding("capital-subscriber", "ZQ026", 100L, 0L, "70000.00");
        insertHoldingSnapshot(
                "ZQ026",
                LocalDate.now().minusDays(5),
                LocalDate.now().minusDays(5).atTime(18, 0)
        );
        insertPaidInCapitalIncrease(
                "ZQ026",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(4),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2)
        );

        assertThat(corporateActionService.applyDueCorporateActions()).isEqualTo(1);
        jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = 'SUBSCRIBED',
                       subscribed_share_quantity = 40,
                       subscribed_cash_amount = 2000000.00,
                       subscribed_at = ?
                 where symbol = 'ZQ026'
                """,
                LocalDateTime.now()
        );
        jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = 'PAID',
                       subscription_end_date = ?,
                       payment_date = ?,
                       listing_date = ?,
                       paid_at = ?
                 where symbol = 'ZQ026'
                """,
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1),
                LocalDate.now(),
                LocalDateTime.now()
        );

        assertThat(corporateActionService.applyDueCorporateActions()).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ026'"))
                .isEqualTo("LISTED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ026'"))
                .isEqualTo(100040L);
        assertThat(queryLong("select tradable_shares from stock_order_book_instrument where symbol = 'ZQ026'"))
                .isEqualTo(100040L);
        assertThat(queryLong("select quantity from stock_holding where symbol = 'ZQ026'"))
                .isEqualTo(140L);
        assertThat(queryDecimal("select average_price from stock_holding where symbol = 'ZQ026'"))
                .isEqualByComparingTo(new BigDecimal("64285.71"));
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ026'"))
                .isEqualTo("PAID");
    }

    @Test
    void applyDueCorporateActions_afterCloseAutoParticipantSubscribesAllocatedRights() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ027", 100000L, 100000L);
        insertPrice("ZQ027", "70000.00");
        insertAccount("capital-auto-holder");
        insertAutoParticipant("capital-auto-holder", "LONG_TERM_HOLDER");
        long accountId = accountIdFor("capital-auto-holder");
        long actionId = insertAppliedPaidInCapitalIncrease(
                "ZQ027",
                50000L,
                "50000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(3)
        );
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                ) values (?, ?, 'ZQ027', 100, 50, 2500000.00, 'ANNOUNCED', null, ?, null)
                """,
                actionId,
                accountId,
                LocalDateTime.now()
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ027'"))
                .isEqualTo("SUBSCRIBED");
        assertThat(queryLong("select subscribed_share_quantity from stock_corporate_action_entitlement where symbol = 'ZQ027'"))
                .isEqualTo(47L);
        assertThat(queryDecimal("select subscribed_cash_amount from stock_corporate_action_entitlement where symbol = 'ZQ027'"))
                .isEqualByComparingTo(new BigDecimal("2350000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'capital-auto-holder'"))
                .isEqualByComparingTo(new BigDecimal("7650000.00"));
        assertThat(queryDecimal("""
                select amount
                  from stock_account_cash_flow f
                  join stock_account a on a.id = f.account_id
                where a.user_key = 'capital-auto-holder'
                   and f.reason = 'CAPITAL_INCREASE_SUBSCRIPTION'
                """)).isEqualByComparingTo(new BigDecimal("2350000.00"));
    }

    @Test
    void applyDueCorporateActions_afterCloseCatchUp_matchesPreOpenDividendThenSubscription() {
        LocalDate today = LocalDate.now();
        LocalDate previousDate = today.minusDays(1);
        setPausedSimulationClock(previousDate, LocalDateTime.now(), today.atTime(5, 30));
        insertCompletedMarketCloseForDate(previousDate);
        insertAccount("capital-order-normal");
        insertAutoParticipant("capital-order-normal", "LONG_TERM_HOLDER");
        long normalAccountId = accountIdFor("capital-order-normal");
        long normalCapitalActionId = insertAppliedPaidInCapitalIncrease(
                "ZQ040",
                100L,
                "50000.00",
                today.minusDays(1),
                today.plusDays(1),
                today.plusDays(2),
                today.plusDays(3)
        );
        insertAnnouncedPaidInEntitlement(normalCapitalActionId, normalAccountId, "ZQ040", 100L);
        long normalDividendActionId = insertAppliedCashDividend("ZQ042", today);
        insertAnnouncedDividendEntitlement(normalDividendActionId, normalAccountId, "ZQ042", "1000000.00");

        corporateActionService.applyDueCorporateActions();

        insertAccount("capital-order-catchup");
        insertAutoParticipant("capital-order-catchup", "LONG_TERM_HOLDER");
        long catchUpAccountId = accountIdFor("capital-order-catchup");
        long catchUpCapitalActionId = insertAppliedPaidInCapitalIncrease(
                "ZQ041",
                100L,
                "50000.00",
                today.minusDays(1),
                today.plusDays(1),
                today.plusDays(2),
                today.plusDays(3)
        );
        insertAnnouncedPaidInEntitlement(catchUpCapitalActionId, catchUpAccountId, "ZQ041", 100L);
        long catchUpDividendActionId = insertAppliedCashDividend("ZQ043", today);
        insertAnnouncedDividendEntitlement(catchUpDividendActionId, catchUpAccountId, "ZQ043", "1000000.00");

        setPausedSimulationClock(today, LocalDateTime.now(), LocalTime.of(19, 0));
        insertCompletedMarketCloseForToday();
        corporateActionService.applyDueCorporateActions();

        String outcome = queryLong("select subscribed_share_quantity from stock_corporate_action_entitlement where action_id = " + normalCapitalActionId)
                + ":" + queryLong("select subscribed_share_quantity from stock_corporate_action_entitlement where action_id = " + catchUpCapitalActionId)
                + ":" + queryDecimal("select cash_balance from stock_account where user_key = 'capital-order-normal'")
                + ":" + queryDecimal("select cash_balance from stock_account where user_key = 'capital-order-catchup'");
        assertThat(outcome).isEqualTo("88:88:6600000.00:6600000.00");
    }

    @Test
    void applyDueCorporateActions_afterCloseAutoParticipantSubscribesPublicOffering() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ028", 100000L, 100000L);
        insertPrice("ZQ028", "70000.00");
        insertAccount("capital-public-auto");
        insertAutoParticipant("capital-public-auto", "LONG_TERM_HOLDER");
        insertPublicOfferingCapitalIncrease(
                "ZQ028",
                100L,
                "50000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(3)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ028'"))
                .isEqualTo("SUBSCRIBED");
        assertThat(queryLong("select subscribed_share_quantity from stock_corporate_action_entitlement where symbol = 'ZQ028'"))
                .isEqualTo(35L);
        assertThat(queryDecimal("select subscribed_cash_amount from stock_corporate_action_entitlement where symbol = 'ZQ028'"))
                .isEqualByComparingTo(new BigDecimal("1750000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'capital-public-auto'"))
                .isEqualByComparingTo(new BigDecimal("8250000.00"));
    }

    @Test
    void applyDueCorporateActions_publicOfferingUsesOriginalOfferingForEachCandidateQuantity() {
        insertCompletedMarketCloseForToday();
        insertAccount("capital-public-first");
        insertAccount("capital-public-second");
        insertAutoParticipant("capital-public-first", "LONG_TERM_HOLDER");
        insertAutoParticipant("capital-public-second", "LONG_TERM_HOLDER");
        insertPublicOfferingCapitalIncrease(
                "ZQ031",
                100L,
                "50000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(3)
        );

        corporateActionService.applyDueCorporateActions();

        assertThat(queryLong("select sum(subscribed_share_quantity) from stock_corporate_action_entitlement where symbol = 'ZQ031'"))
                .isEqualTo(70L);
    }

    @Test
    void applyDueCorporateActions_repeatedPublicOfferingRunDoesNotDoubleWithdrawOrSubscribe() {
        insertCompletedMarketCloseForToday();
        insertAccount("capital-public-idempotent");
        insertAutoParticipant("capital-public-idempotent", "LONG_TERM_HOLDER");
        insertPublicOfferingCapitalIncrease(
                "ZQ037",
                100L,
                "50000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(3)
        );

        corporateActionService.applyDueCorporateActions();
        corporateActionService.applyDueCorporateActions();

        String outcome = queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ037'")
                + ":" + queryLong("""
                select count(*)
                  from stock_account_cash_flow f
                  join stock_account a on a.id = f.account_id
                 where a.user_key = 'capital-public-idempotent'
                   and f.reason = 'CAPITAL_INCREASE_SUBSCRIPTION'
                """)
                + ":" + queryDecimal("select cash_balance from stock_account where user_key = 'capital-public-idempotent'");

        assertThat(outcome).isEqualTo("1:1:8250000.00");
    }

    @Test
    void applyDueCorporateActions_regularSessionDoesNotAutoSubscribePublicOffering() {
        setPausedSimulationClock(LocalDate.now(), LocalDateTime.now(), LocalTime.of(10, 0));
        insertCompletedMarketCloseForToday();
        insertAccount("capital-public-regular");
        insertAutoParticipant("capital-public-regular", "LONG_TERM_HOLDER");
        insertPublicOfferingCapitalIncrease(
                "ZQ038",
                100L,
                "50000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2),
                LocalDate.now().plusDays(3)
        );

        corporateActionService.applyDueCorporateActions();

        assertThat(queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ038'"))
                .isZero();
    }

    @Test
    void applyDueCorporateActions_shareholderPaymentExpiresUnsubscribedEntitlement() {
        insertCompletedMarketCloseForToday();
        insertAccount("capital-unsubscribed-holder");
        long actionId = insertAppliedPaidInCapitalIncrease(
                "ZQ033",
                100L,
                "50000.00",
                LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(1),
                LocalDate.now(),
                LocalDate.now().plusDays(1)
        );
        insertAnnouncedPaidInEntitlement(
                actionId,
                accountIdFor("capital-unsubscribed-holder"),
                "ZQ033",
                10L
        );

        corporateActionService.applyDueCorporateActions();

        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ033'"))
                .isEqualTo("EXPIRED");
    }

    @Test
    void applyDueCorporateActions_paidInListingFailureDoesNotRollbackDividendPayment() {
        insertCompletedMarketCloseForToday();
        insertAccount("dividend-isolated-holder");
        long dividendActionId = insertAppliedCashDividend("ZQ034", LocalDate.now());
        insertAnnouncedDividendEntitlement(
                dividendActionId,
                accountIdFor("dividend-isolated-holder"),
                "ZQ034",
                "10000.00"
        );
        insertAccount("capital-broken-listing-holder");
        long paidInActionId = insertAppliedPaidInCapitalIncrease(
                "ZQ035",
                100L,
                "50000.00",
                LocalDate.now().minusDays(4),
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1),
                LocalDate.now()
        );
        jdbcTemplate.update(
                "update stock_corporate_action set status = 'PAID', paid_at = ? where id = ?",
                LocalDateTime.now(),
                paidInActionId
        );
        insertSubscribedPaidInEntitlement(
                paidInActionId,
                accountIdFor("capital-broken-listing-holder"),
                "ZQ035",
                1L,
                "50000.00"
        );

        Throwable failure = catchThrowable(corporateActionService::applyDueCorporateActions);
        String outcome = failure.getClass().getSimpleName()
                + ":" + queryString("select status from stock_corporate_action where id = " + dividendActionId)
                + ":" + queryDecimal("select cash_balance from stock_account where user_key = 'dividend-isolated-holder'");

        assertThat(outcome).isEqualTo("IllegalStateException:PAID:10010000.00");
    }

    @Test
    void applyDueCorporateActions_beforeListingDate_keepsShareCountsPending() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ002", 100000L, 100000L);
        insertPrice("ZQ002", "70000.00");
        insertHoldingSnapshot(
                "ZQ002",
                LocalDate.now().minusDays(4),
                LocalDate.now().minusDays(4).atTime(18, 0)
        );
        insertPaidInCapitalIncrease(
                "ZQ002",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(3),
                LocalDate.now(),
                LocalDate.now().plusDays(3)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(2);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ002'"))
                .isEqualTo("PAID");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ002'"))
                .isEqualTo(100000L);
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ002'"))
                .isEqualByComparingTo(new BigDecimal("63333.00"));
    }

    @Test
    void applyDueCorporateActions_exRightsWithOpenOrder_waitsWithoutChangingPrice() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ011", 100000L, 100000L);
        insertPrice("ZQ011", "70000.00");
        insertAccount("rights-open-buyer");
        insertOpenOrder("rights-open-order", "rights-open-buyer", "ZQ011");
        insertPaidInCapitalIncrease(
                "ZQ011",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(2),
                LocalDate.now(),
                LocalDate.now().plusDays(3)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ011'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ011'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void applyDueCorporateActions_shareholderAllocationWithoutHoldingSnapshot_waitsWithoutCreatingRights() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ030", 100000L, 100000L);
        insertPrice("ZQ030", "70000.00");
        insertAccount("rights-no-snapshot-holder");
        insertHolding("rights-no-snapshot-holder", "ZQ030", 10L, 0L, "70000.00");
        insertPaidInCapitalIncrease(
                "ZQ030",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(2),
                LocalDate.now(),
                LocalDate.now().plusDays(3)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ030'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ030'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ030'"))
                .isZero();
    }

    @Test
    void applyDueCorporateActions_cashDividendWithOpenOrder_waitsWithoutCreatingEntitlements() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ013", 100000L, 100000L);
        insertPrice("ZQ013", "70000.00");
        insertAccount("dividend-open-holder");
        insertHolding("dividend-open-holder", "ZQ013", 10L, 0L, "70000.00");
        insertOpenOrder("dividend-open-order", "dividend-open-holder", "ZQ013");
        insertCashDividend(
                "ZQ013",
                "1000.00",
                "70000.00",
                "69000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ013'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ013'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ013'"))
                .isZero();
    }

    @Test
    void applyDueCorporateActions_bonusIssueWithOpenOrder_waitsWithoutCreatingEntitlements() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ014", 100000L, 100000L);
        insertPrice("ZQ014", "70000.00");
        insertAccount("bonus-open-holder");
        insertHolding("bonus-open-holder", "ZQ014", 100L, 0L, "70000.00");
        insertOpenOrder("bonus-open-order", "bonus-open-holder", "ZQ014");
        insertBonusIssue(
                "ZQ014",
                10000L,
                "70000.00",
                "63636.36",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ014'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ014'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ014'"))
                .isZero();
    }

    @Test
    void applyDueCorporateActions_cashDividendWithoutHoldingSnapshot_waitsWithoutCreatingEntitlements() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ016", 100000L, 100000L);
        insertPrice("ZQ016", "70000.00");
        insertAccount("dividend-no-snapshot-holder");
        insertHolding("dividend-no-snapshot-holder", "ZQ016", 10L, 0L, "70000.00");
        insertCashDividend(
                "ZQ016",
                "1000.00",
                "70000.00",
                "69000.00",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ016'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ016'"))
                .isZero();
    }

    @Test
    void applyDueCorporateActions_cashDividendUsesLatestCloseBeforeExRightsDateWhenMultipleClosesExist() {
        insertCompletedMarketCloseForToday();
        LocalDate exRightsDate = LocalDate.now();
        insertOrderBookInstrument("ZQ017", 100000L, 100000L);
        insertPrice("ZQ017", "70000.00");
        insertAccount("dividend-multi-close-holder");
        insertHolding("dividend-multi-close-holder", "ZQ017", 10L, 0L, "70000.00");
        insertHoldingSnapshot(
                "ZQ017",
                exRightsDate.minusDays(2),
                exRightsDate.minusDays(2).atTime(18, 0)
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 25,
                       updated_at = ?
                 where symbol = 'ZQ017'
                """,
                LocalDateTime.now().minusHours(2)
        );
        long latestCloseRunId = insertHoldingSnapshot(
                "ZQ017",
                exRightsDate.minusDays(1),
                exRightsDate.minusDays(1).atTime(18, 0)
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 99,
                       updated_at = ?
                 where symbol = 'ZQ017'
                """,
                LocalDateTime.now()
        );
        insertCashDividend(
                "ZQ017",
                "1000.00",
                "70000.00",
                "69000.00",
                exRightsDate,
                LocalDate.now().plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ017'"))
                .isEqualTo("EX_RIGHTS_APPLIED");
        assertThat(queryLong("select quantity from stock_corporate_action_entitlement where symbol = 'ZQ017'"))
                .isEqualTo(25L);
        assertThat(queryLong("select holding_snapshot_run_id from stock_corporate_action_entitlement where symbol = 'ZQ017'"))
                .isEqualTo(latestCloseRunId);
        assertThat(queryLong("select quantity from stock_holding where symbol = 'ZQ017'"))
                .isEqualTo(99L);
    }

    @Test
    void applyDueCorporateActions_cashDividendDoesNotReuseStaleSnapshotWhenLatestCloseHasNoHolder() {
        insertCompletedMarketCloseForToday();
        LocalDate exRightsDate = LocalDate.now();
        insertOrderBookInstrument("ZQ018", 100000L, 100000L);
        insertPrice("ZQ018", "70000.00");
        insertAccount("dividend-stale-snapshot-holder");
        insertHolding("dividend-stale-snapshot-holder", "ZQ018", 10L, 0L, "70000.00");
        insertHoldingSnapshot(
                "ZQ018",
                exRightsDate.minusDays(2),
                exRightsDate.minusDays(2).atTime(18, 0)
        );
        jdbcTemplate.update("delete from stock_holding where symbol = 'ZQ018'");
        long latestCloseRunId = insertMarketCloseRun(exRightsDate.minusDays(1), exRightsDate.minusDays(1).atTime(18, 0));
        insertCashDividend(
                "ZQ018",
                "1000.00",
                "70000.00",
                "69000.00",
                exRightsDate,
                LocalDate.now().plusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ018'"))
                .isEqualTo("EX_RIGHTS_APPLIED");
        assertThat(queryLong("select count(*) from stock_holding_snapshot where close_run_id = " + latestCloseRunId + " and symbol = 'ZQ018'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_corporate_action_entitlement where symbol = 'ZQ018'"))
                .isZero();
    }

    @Test
    void applyDueCorporateActions_zeroValueDelisting_cancelsOrdersAndDisablesTrading() {
        insertCompletedMarketCloseForToday();
        insertOrderBookInstrument("ZQ015", 100000L, 100000L);
        insertPrice("ZQ015", "70000.00");
        insertOrderBookMarketConfig("ZQ015");
        jdbcTemplate.update(
                "update stock_order_book_market_config set market_status = 'CLOSED' where symbol = 'ZQ015'"
        );
        insertAutoMarketConfig("ZQ015");
        insertListingAutoConfig("ZQ015");
        insertParticipantSymbolConfig("ZQ015");
        insertAccount("delisting-buyer");
        insertAccount("delisting-seller");
        insertHolding("delisting-seller", "ZQ015", 10L, 4L, "70000.00");
        insertReservedBuyOrder("delisting-buy-order", "delisting-buyer", "ZQ015", "140000.00");
        insertReservedSellOrder("delisting-sell-order", "delisting-seller", "ZQ015", 4L);
        insertDelisting("ZQ015", LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ015'"))
                .isEqualTo("DELISTED");
        assertThat(queryLong("select case when enabled then 1 else 0 end from stock_order_book_instrument where symbol = 'ZQ015'"))
                .isZero();
        assertThat(queryLong("select tradable_shares from stock_order_book_instrument where symbol = 'ZQ015'"))
                .isZero();
        assertThat(queryString("select market_status from stock_order_book_market_config where symbol = 'ZQ015'"))
                .isEqualTo("HALTED");
        assertThat(queryLong("select case when enabled then 1 else 0 end from stock_auto_market_config where symbol = 'ZQ015'"))
                .isZero();
        assertThat(queryLong("select case when enabled then 1 else 0 end from stock_listing_auto_account_config where symbol = 'ZQ015'"))
                .isZero();
        assertThat(queryLong("select case when enabled then 1 else 0 end from stock_auto_participant_symbol_config where symbol = 'ZQ015'"))
                .isZero();
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ015'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select previous_close from stock_price where symbol = 'ZQ015'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryString("select provider from stock_price where symbol = 'ZQ015'"))
                .isEqualTo("corporate-action-delisting-zero");
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = 'ZQ015' and price = 0 and provider = 'corporate-action-delisting-zero'"))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_order where symbol = 'ZQ015' and status = 'CANCELLED' and reserved_cash = 0"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'delisting-buyer'"))
                .isEqualByComparingTo(new BigDecimal("10000000.00"));
        assertThat(queryLong("select reserved_quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'delisting-seller' and h.symbol = 'ZQ015'"))
                .isZero();
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'delisting-seller' and h.symbol = 'ZQ015'"))
                .isEqualTo(10L);
    }

    private void insertOrderBookInstrument(String symbol, long issuedShares, long tradableShares) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, enabled, created_at, updated_at)
                values (?, ?, 'ORDERBOOK', ?, ?, ?, true, ?, ?)
                """,
                symbol,
                symbol + " 종목",
                new BigDecimal("70000.00"),
                issuedShares,
                tradableShares,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void setPausedSimulationClock(LocalDate baseDate, LocalDateTime lastHeartbeatAt, LocalTime simulationTime) {
        setPausedSimulationClock(baseDate, lastHeartbeatAt, baseDate.atTime(simulationTime));
    }

    private void setPausedSimulationClock(LocalDate baseDate, LocalDateTime lastHeartbeatAt, LocalDateTime simulationDateTime) {
        jdbcTemplate.update("delete from stock_simulation_clock");
        long dayOffset = Duration.between(baseDate.atStartOfDay(), simulationDateTime.toLocalDate().atStartOfDay()).toDays();
        long accumulatedRealSeconds = dayOffset * 7200L
                + simulationDateTime.toLocalTime().toSecondOfDay() * 7200L / Duration.ofDays(1).toSeconds();
        jdbcTemplate.update(
                """
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
                values ('DEFAULT', ?, 7200, ?, false, null, ?, 'Asia/Seoul', ?, ?)
                """,
                baseDate,
                accumulatedRealSeconds,
                lastHeartbeatAt,
                lastHeartbeatAt,
                lastHeartbeatAt
        );
        jdbcTemplate.update(
                """
                merge into stock_market_business_state(
                    state_id,
                    active_business_date,
                    preparing_business_date,
                    raw_simulation_date,
                    version,
                    created_at,
                    updated_at
                ) key(state_id)
                values ('DEFAULT', ?, null, ?, 0, ?, ?)
                """,
                simulationDateTime.toLocalDate(),
                simulationDateTime.toLocalDate(),
                lastHeartbeatAt,
                lastHeartbeatAt
        );
    }

    private void insertCompletedMarketCloseForToday() {
        insertMarketCloseRun(LocalDate.now(), LocalDate.now().atTime(18, 0));
    }

    private void insertCompletedSymbolMarketCloseForToday(String symbol) {
        LocalDate today = LocalDate.now();
        LocalDateTime closedAt = today.atTime(18, 0);
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(
                    symbol, business_date, closed_at, status,
                    cancelled_order_count, holding_snapshot_count, price_rollover_count,
                    created_at, completed_at
                )
                values (?, ?, ?, 'COMPLETED', 0, 0, 0, ?, ?)
                """,
                symbol,
                today,
                closedAt,
                closedAt,
                closedAt
        );
    }

    private void insertCompletedMarketCloseForDate(LocalDate businessDate) {
        insertMarketCloseRun(businessDate, businessDate.atTime(18, 0));
    }

    private void insertPrice(String symbol, String price) {
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close, price_time, provider) values (?, ?, ?, ?, 'test')",
                symbol,
                new BigDecimal(price),
                new BigDecimal(price),
                LocalDateTime.now()
        );
    }

    private void insertAccount(String userKey) {
        jdbcTemplate.update(
                "insert into stock_account(user_key, cash_balance, created_at, updated_at) values (?, ?, ?, ?)",
                userKey,
                new BigDecimal("10000000.00"),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', 10000000.00, 'OPENING_GRANT', 'SYSTEM', ?
                from stock_account
                where user_key = ?
                """,
                LocalDateTime.now(),
                userKey
        );
    }

    private void insertAutoParticipant(String userKey, String profileType) {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(user_key, display_name, enabled, profile_type, created_at, updated_at, withdrawn_at)
                values (?, ?, true, ?, ?, ?, null)
                """,
                userKey,
                userKey,
                profileType,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertHolding(String userKey, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                accountId,
                symbol,
                quantity,
                reservedQuantity,
                new BigDecimal(averagePrice),
                LocalDateTime.now()
        );
    }

    private void insertOpenOrder(String clientOrderId, String userKey, String symbol) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 70000.00, 1, 0, 70000.00, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertReservedBuyOrder(String clientOrderId, String userKey, String symbol, String reservedCash) {
        Long accountId = accountIdFor(userKey);
        BigDecimal reservedCashAmount = new BigDecimal(reservedCash);
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance - ?, updated_at = ? where id = ?",
                reservedCashAmount,
                LocalDateTime.now(),
                accountId
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 70000.00, 2, 0, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                reservedCashAmount,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertReservedSellOrder(String clientOrderId, String userKey, String symbol, long quantity) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING', 70000.00, ?, 0, 0, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                quantity,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertOrderBookMarketConfig(String symbol) {
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at) values (?, true, 'OPEN', ?)",
                symbol,
                LocalDateTime.now()
        );
    }

    private void insertAutoMarketConfig(String symbol) {
        jdbcTemplate.update(
                "insert into stock_auto_market_config(symbol, enabled, max_order_quantity, order_ttl_seconds, updated_at) values (?, true, 3, 15, ?)",
                symbol,
                LocalDateTime.now()
        );
    }

    private void insertListingAutoConfig(String symbol) {
        jdbcTemplate.update(
                """
                insert into stock_listing_auto_account_config(
                    symbol, user_key, display_name, enabled, position_side,
                    operation_mode, strategy_profile, initial_inventory_quantity, initial_issue_price,
                    max_order_quantity, order_ttl_seconds, price_offset_ticks,
                    target_spread_ticks, inventory_skew_ticks, minimum_profit_rate,
                    aggressive_unwind_threshold, aggressive_order_ratio,
                    target_buy_quantity, target_sell_quantity, target_holding_quantity, inventory_band_quantity,
                    created_at, updated_at
                ) values (?, ?, '상장주관사', true, 'SELL_ONLY',
                          'UNDERWRITER_RETURN', 'RETURN_FIRST', 1000000, 70000.00,
                          10, 30, 3, 8, 3, 1.0, 1.0, 0.0,
                          0, 10, 0, 0, ?, ?)
                """,
                symbol,
                "stock-listing-" + symbol.toLowerCase(Locale.ROOT),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertParticipantSymbolConfig(String symbol) {
        jdbcTemplate.update(
                "insert into stock_auto_participant_symbol_config(user_key, symbol, enabled, intensity, updated_at) values ('stock-auto-test', ?, true, 5, ?)",
                symbol,
                LocalDateTime.now()
        );
    }

    private long insertHoldingSnapshot(String symbol) {
        return insertHoldingSnapshot(symbol, LocalDateTime.now());
    }

    private long insertHoldingSnapshot(String symbol, LocalDateTime snapshotAt) {
        return insertHoldingSnapshot(symbol, LocalDate.now(), snapshotAt);
    }

    private long insertHoldingSnapshot(String symbol, LocalDate businessDate, LocalDateTime snapshotAt) {
        Long closeRunId = insertMarketCloseRun(businessDate, snapshotAt);
        jdbcTemplate.update(
                """
                insert into stock_holding_snapshot(
                    close_run_id, account_id, symbol, quantity, reserved_quantity, average_price, snapshot_at
                )
                select ?, account_id, symbol, quantity, reserved_quantity, average_price, ?
                  from stock_holding
                 where symbol = ?
                """,
                closeRunId,
                snapshotAt,
                symbol
        );
        jdbcTemplate.update(
                """
                update stock_market_close_run
                   set holding_snapshot_count = (
                       select count(*)
                         from stock_holding_snapshot
                        where close_run_id = ?
                   )
                 where id = ?
                """,
                closeRunId,
                closeRunId
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_daily_snapshot(
                    close_run_id, symbol, simulation_trade_date, snapshot_at, created_at,
                    name, market, enabled, market_enabled, market_status,
                    issued_shares, tradable_shares, initial_price, tick_size, price_limit_rate,
                    close_price, previous_close, change_rate
                )
                select ?, i.symbol, ?, ?, ?,
                       i.name, i.market, i.enabled, false, 'CLOSED',
                       i.issued_shares, i.tradable_shares, i.initial_price, i.tick_size, i.price_limit_rate,
                       coalesce(p.current_price, i.initial_price),
                       coalesce(p.previous_close, i.initial_price),
                       0
                  from stock_order_book_instrument i
                  left join stock_price p on p.symbol = i.symbol
                 where i.symbol = ?
                """,
                closeRunId,
                businessDate,
                snapshotAt,
                snapshotAt,
                symbol
        );
        return closeRunId;
    }

    private long insertMarketCloseRun(LocalDateTime snapshotAt) {
        return insertMarketCloseRun(LocalDate.now(), snapshotAt);
    }

    private long insertMarketCloseRun(LocalDate businessDate, LocalDateTime snapshotAt) {
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(
                    business_date, closed_at, status,
                    cancelled_order_count, holding_snapshot_count, price_rollover_count,
                    created_at, completed_at
                )
                values (?, ?, 'COMPLETED', 0, 0, 0, ?, ?)
                """,
                businessDate,
                snapshotAt,
                snapshotAt,
                snapshotAt
        );
        return jdbcTemplate.queryForObject(
                "select max(id) from stock_market_close_run",
                Long.class
        );
    }

    private Long accountIdFor(String userKey) {
        return jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                userKey
        );
    }

    private void insertStockSplit(String symbol, int splitFrom, int splitTo, LocalDate listingDate) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, status, split_from, split_to, listing_date, description, created_at
                ) values (?, 'STOCK_SPLIT', 'ANNOUNCED', ?, ?, ?, 'test', ?)
                """,
                symbol,
                splitFrom,
                splitTo,
                listingDate,
                LocalDateTime.now()
        );
    }

    private void insertPaidInCapitalIncrease(
            String symbol,
            long shareQuantity,
            String issuePrice,
            String basePrice,
            String theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate paymentDate,
            LocalDate listingDate
    ) {
        jdbcTemplate.update(
                """
	                insert into stock_corporate_action(
	                  symbol, action_type, share_quantity, issue_price, status, base_price,
	                  theoretical_ex_rights_price, ex_rights_date, subscription_start_date, subscription_end_date,
	                  payment_date, listing_date, offering_type, description, created_at
	                ) values (?, 'PAID_IN_CAPITAL_INCREASE', ?, ?, 'ANNOUNCED', ?, ?, ?, ?, ?, ?, ?, 'SHAREHOLDER_ALLOCATION', 'test', ?)
	                """,
                symbol,
                shareQuantity,
                new BigDecimal(issuePrice),
                new BigDecimal(basePrice),
                new BigDecimal(theoreticalExRightsPrice),
                exRightsDate,
                exRightsDate.plusDays(1),
                paymentDate.minusDays(1),
                paymentDate,
                listingDate,
                LocalDateTime.now()
        );
    }

    private long insertAppliedPaidInCapitalIncrease(
            String symbol,
            long shareQuantity,
            String issuePrice,
            LocalDate subscriptionStartDate,
            LocalDate subscriptionEndDate,
            LocalDate paymentDate,
            LocalDate listingDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, share_quantity, issue_price, status, base_price,
                  theoretical_ex_rights_price, ex_rights_date, subscription_start_date, subscription_end_date,
                  payment_date, listing_date, offering_type, applied_at, description, created_at
                ) values (?, 'PAID_IN_CAPITAL_INCREASE', ?, ?, 'EX_RIGHTS_APPLIED', 70000.00, 63333.33, ?, ?, ?, ?, ?, 'SHAREHOLDER_ALLOCATION', ?, 'test', ?)
                """,
                symbol,
                shareQuantity,
                new BigDecimal(issuePrice),
                subscriptionStartDate.minusDays(1),
                subscriptionStartDate,
                subscriptionEndDate,
                paymentDate,
                listingDate,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        return queryLong("select max(id) from stock_corporate_action where symbol = '" + symbol + "'");
    }

    private void insertPublicOfferingCapitalIncrease(
            String symbol,
            long shareQuantity,
            String issuePrice,
            LocalDate subscriptionStartDate,
            LocalDate subscriptionEndDate,
            LocalDate paymentDate,
            LocalDate listingDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, share_quantity, issue_price, status,
                  subscription_start_date, subscription_end_date, payment_date, listing_date,
                  offering_type, description, created_at
                ) values (?, 'PAID_IN_CAPITAL_INCREASE', ?, ?, 'ANNOUNCED', ?, ?, ?, ?, 'PUBLIC_OFFERING', 'test', ?)
                """,
                symbol,
                shareQuantity,
                new BigDecimal(issuePrice),
                subscriptionStartDate,
                subscriptionEndDate,
                paymentDate,
                listingDate,
                LocalDateTime.now()
        );
    }

    private long insertAppliedCashDividend(String symbol, LocalDate paymentDate) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, dividend_amount, status, base_price,
                  theoretical_ex_rights_price, ex_rights_date, payment_date, applied_at, description, created_at
                ) values (?, 'CASH_DIVIDEND', 1000.00, 'EX_RIGHTS_APPLIED', 70000.00,
                          70000.00, ?, ?, ?, 'test', ?)
                """,
                symbol,
                paymentDate.minusDays(1),
                paymentDate,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        return queryLong("select max(id) from stock_corporate_action where symbol = '" + symbol + "'");
    }

    private void insertAnnouncedDividendEntitlement(
            long actionId,
            long accountId,
            String symbol,
            String cashAmount
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                ) values (?, ?, ?, 10, null, ?, 'ANNOUNCED', null, ?, null, null)
                """,
                actionId,
                accountId,
                symbol,
                new BigDecimal(cashAmount),
                LocalDateTime.now()
        );
    }

    private void insertAnnouncedPaidInEntitlement(
            long actionId,
            long accountId,
            String symbol,
            long shareQuantity
    ) {
        BigDecimal cashAmount = new BigDecimal("50000.00").multiply(BigDecimal.valueOf(shareQuantity));
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                ) values (?, ?, ?, ?, ?, ?, 'ANNOUNCED', null, ?, null, null)
                """,
                actionId,
                accountId,
                symbol,
                shareQuantity,
                shareQuantity,
                cashAmount,
                LocalDateTime.now()
        );
    }

    private void insertSubscribedPaidInEntitlement(
            long actionId,
            long accountId,
            String symbol,
            long shareQuantity,
            String cashAmount
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                  subscribed_share_quantity, subscribed_cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'SUBSCRIBED', null, ?, ?, null)
                """,
                actionId,
                accountId,
                symbol,
                shareQuantity,
                shareQuantity,
                new BigDecimal(cashAmount),
                shareQuantity,
                new BigDecimal(cashAmount),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertBonusIssue(
            String symbol,
            long shareQuantity,
            String basePrice,
            String theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate listingDate
    ) {
        insertFreeShareDistribution(symbol, "BONUS_ISSUE", shareQuantity, basePrice, theoreticalExRightsPrice, exRightsDate, listingDate);
    }

    private void insertFreeShareDistribution(
            String symbol,
            String actionType,
            long shareQuantity,
            String basePrice,
            String theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate listingDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, share_quantity, status, base_price,
                  theoretical_ex_rights_price, ex_rights_date, listing_date, description, created_at
                ) values (?, ?, ?, 'ANNOUNCED', ?, ?, ?, ?, 'test', ?)
                """,
                symbol,
                actionType,
                shareQuantity,
                new BigDecimal(basePrice),
                new BigDecimal(theoreticalExRightsPrice),
                exRightsDate,
                listingDate,
                LocalDateTime.now()
        );
    }

    private void insertCashDividend(
            String symbol,
            String dividendAmount,
            String basePrice,
            String theoreticalExRightsPrice,
            LocalDate exRightsDate,
            LocalDate paymentDate
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, dividend_amount, status, base_price,
                  theoretical_ex_rights_price, ex_rights_date, payment_date, description, created_at
                ) values (?, 'CASH_DIVIDEND', ?, 'ANNOUNCED', ?, ?, ?, ?, 'test', ?)
                """,
                symbol,
                new BigDecimal(dividendAmount),
                new BigDecimal(basePrice),
                new BigDecimal(theoreticalExRightsPrice),
                exRightsDate,
                paymentDate,
                LocalDateTime.now()
        );
    }

    private void insertDelisting(String symbol, LocalDate delistingDate) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, status, delisting_date, delisting_treatment, description, created_at
                ) values (?, 'DELISTING', 'ANNOUNCED', ?, 'ZERO_VALUE', 'test delisting', ?)
                """,
                symbol,
                delistingDate,
                LocalDateTime.now()
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private LocalDateTime queryDateTime(String sql) {
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class);
    }
}
