package stock.batch.service.corporateaction.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class CorporateActionServiceTest {

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
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_account_cash_flow where account_id in (select id from stock_account where user_key like 'split-%' or user_key like 'dividend-%' or user_key like 'bonus-%')");
        jdbcTemplate.update("delete from stock_account where user_key like 'split-%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'dividend-%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'bonus-%'");
        jdbcTemplate.update("delete from stock_corporate_action_entitlement where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_corporate_action where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_price_tick where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_price where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_listing_auto_account_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_auto_market_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_order_book_market_config where symbol like 'ZQ%'");
        jdbcTemplate.update("delete from stock_order_book_instrument where symbol like 'ZQ%'");
    }

    @Test
    void applyDueCorporateActions_additionalIssueOnListingDate_increasesSharesWithoutPriceReset() {
        insertOrderBookInstrument("ZQ003", 100000L, 100000L);
        insertPrice("ZQ003", "70000.00");
        insertAdditionalIssue("ZQ003", 30000L, "60000.00", LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ003'"))
                .isEqualTo("LISTED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ003'"))
                .isEqualTo(130000L);
        assertThat(queryLong("select tradable_shares from stock_order_book_instrument where symbol = 'ZQ003'"))
                .isEqualTo(130000L);
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ003'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void corporateActionTable_additionalIssueWithoutIssuePrice_rejectsInvalidLedgerRow() {
        insertOrderBookInstrument("ZQ006", 100000L, 100000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, share_quantity, status, listing_date, description, created_at
                ) values (?, 'ADDITIONAL_ISSUE', ?, 'ANNOUNCED', ?, 'test', ?)
                """,
                "ZQ006",
                30000L,
                LocalDate.now().plusDays(1),
                LocalDateTime.now()
        ))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void applyDueCorporateActions_cashDividendOnPaymentDate_keepsPriceAndPaysEntitlements() {
        insertOrderBookInstrument("ZQ007", 100000L, 100000L);
        insertPrice("ZQ007", "70000.00");
        insertAccount("dividend-holder");
        insertHolding("dividend-holder", "ZQ007", 10L, 0L, "70000.00");
        long closeRunId = insertHoldingSnapshot("ZQ007");
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
        insertOrderBookInstrument("ZQ009", 100000L, 100000L);
        insertPrice("ZQ009", "70000.00");
        insertAccount("bonus-holder");
        insertHolding("bonus-holder", "ZQ009", 100L, 0L, "70000.00");
        insertHoldingSnapshot("ZQ009");
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
                .isEqualByComparingTo(new BigDecimal("63636.36"));
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
        insertOrderBookInstrument("ZQ010", 100000L, 100000L);
        insertPrice("ZQ010", "70000.00");
        insertAccount("dividend-stock-holder");
        insertHolding("dividend-stock-holder", "ZQ010", 100L, 0L, "70000.00");
        insertHoldingSnapshot("ZQ010");
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
                .isEqualByComparingTo(new BigDecimal("63636.36"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'dividend-stock-holder' and symbol = 'ZQ010'"))
                .isEqualTo(110L);
        assertThat(queryLong("select share_quantity from stock_corporate_action_entitlement where symbol = 'ZQ010'"))
                .isEqualTo(10L);
        assertThat(queryString("select status from stock_corporate_action_entitlement where symbol = 'ZQ010'"))
                .isEqualTo("PAID");
    }

    @Test
    void applyDueCorporateActions_stockSplitOnEffectiveDate_adjustsSharesHoldingsAndPrice() {
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
    void applyDueCorporateActions_exRightsPaymentAndListing_updatesPriceThenShares() {
        insertOrderBookInstrument("ZQ001", 100000L, 100000L);
        insertPrice("ZQ001", "70000.00");
        insertPaidInCapitalIncrease(
                "ZQ001",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(2),
                LocalDate.now().minusDays(1)
        );

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isEqualTo(3);
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ001'"))
                .isEqualTo("LISTED");
        assertThat(queryDecimal("select current_price from stock_price where symbol = 'ZQ001'"))
                .isEqualByComparingTo(new BigDecimal("63333.33"));
        assertThat(queryString("select provider from stock_price where symbol = 'ZQ001'"))
                .isEqualTo("corporate-action-rights");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ001'"))
                .isEqualTo(150000L);
        assertThat(queryLong("select tradable_shares from stock_order_book_instrument where symbol = 'ZQ001'"))
                .isEqualTo(150000L);
        assertThat(queryLong("select count(*) from stock_price_tick where symbol = 'ZQ001' and provider = 'corporate-action-rights'"))
                .isEqualTo(1L);
    }

    @Test
    void applyDueCorporateActions_beforeListingDate_keepsShareCountsPending() {
        insertOrderBookInstrument("ZQ002", 100000L, 100000L);
        insertPrice("ZQ002", "70000.00");
        insertPaidInCapitalIncrease(
                "ZQ002",
                50000L,
                "50000.00",
                "70000.00",
                "63333.33",
                LocalDate.now().minusDays(1),
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
                .isEqualByComparingTo(new BigDecimal("63333.33"));
    }

    @Test
    void applyDueCorporateActions_exRightsWithOpenOrder_waitsWithoutChangingPrice() {
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
                LocalDate.now().minusDays(1),
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
    void applyDueCorporateActions_cashDividendWithOpenOrder_waitsWithoutCreatingEntitlements() {
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
    void applyDueCorporateActions_cashDividendUsesLatestCloseSnapshotWhenMultipleClosesExist() {
        insertOrderBookInstrument("ZQ017", 100000L, 100000L);
        insertPrice("ZQ017", "70000.00");
        insertAccount("dividend-multi-close-holder");
        insertHolding("dividend-multi-close-holder", "ZQ017", 10L, 0L, "70000.00");
        insertHoldingSnapshot("ZQ017", LocalDateTime.now().minusHours(3));
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 25,
                       updated_at = ?
                 where symbol = 'ZQ017'
                """,
                LocalDateTime.now().minusHours(2)
        );
        long latestCloseRunId = insertHoldingSnapshot("ZQ017", LocalDateTime.now().minusHours(1));
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
                LocalDate.now().minusDays(1),
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
        insertOrderBookInstrument("ZQ018", 100000L, 100000L);
        insertPrice("ZQ018", "70000.00");
        insertAccount("dividend-stale-snapshot-holder");
        insertHolding("dividend-stale-snapshot-holder", "ZQ018", 10L, 0L, "70000.00");
        insertHoldingSnapshot("ZQ018", LocalDateTime.now().minusHours(3));
        jdbcTemplate.update("delete from stock_holding where symbol = 'ZQ018'");
        long latestCloseRunId = insertMarketCloseRun(LocalDateTime.now().minusHours(1));
        insertCashDividend(
                "ZQ018",
                "1000.00",
                "70000.00",
                "69000.00",
                LocalDate.now().minusDays(1),
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
    void applyDueCorporateActions_additionalIssueWithOpenOrder_waitsWithoutChangingShares() {
        insertOrderBookInstrument("ZQ012", 100000L, 100000L);
        insertPrice("ZQ012", "70000.00");
        insertAccount("additional-open-buyer");
        insertOpenOrder("additional-open-order", "additional-open-buyer", "ZQ012");
        insertAdditionalIssue("ZQ012", 30000L, "60000.00", LocalDate.now().minusDays(1));

        int processedCount = corporateActionService.applyDueCorporateActions();

        assertThat(processedCount).isZero();
        assertThat(queryString("select status from stock_corporate_action where symbol = 'ZQ012'"))
                .isEqualTo("ANNOUNCED");
        assertThat(queryLong("select issued_shares from stock_order_book_instrument where symbol = 'ZQ012'"))
                .isEqualTo(100000L);
    }

    @Test
    void applyDueCorporateActions_zeroValueDelisting_cancelsOrdersAndDisablesTrading() {
        insertOrderBookInstrument("ZQ015", 100000L, 100000L);
        insertPrice("ZQ015", "70000.00");
        insertOrderBookMarketConfig("ZQ015");
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
                "insert into stock_auto_market_config(symbol, enabled, intensity, max_order_quantity, order_ttl_seconds, updated_at) values (?, true, 5, 3, 15, ?)",
                symbol,
                LocalDateTime.now()
        );
    }

    private void insertListingAutoConfig(String symbol) {
        jdbcTemplate.update(
                """
                insert into stock_listing_auto_account_config(
                    symbol, user_key, display_name, enabled, position_side, max_order_quantity,
                    order_ttl_seconds, price_offset_ticks, created_at, updated_at
                ) values (?, ?, '상장주관사', true, 'SELL_ONLY', 10, 30, 3, ?, ?)
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
        Long closeRunId = insertMarketCloseRun(snapshotAt);
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
        return closeRunId;
    }

    private long insertMarketCloseRun(LocalDateTime snapshotAt) {
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(
                    business_date, closed_at, status,
                    cancelled_order_count, holding_snapshot_count, price_rollover_count,
                    created_at, completed_at
                )
                values (?, ?, 'COMPLETED', 0, 0, 0, ?, ?)
                """,
                snapshotAt.toLocalDate(),
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

    private void insertAdditionalIssue(String symbol, long shareQuantity, String issuePrice, LocalDate listingDate) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action(
                  symbol, action_type, share_quantity, issue_price, status, listing_date, description, created_at
                ) values (?, 'ADDITIONAL_ISSUE', ?, ?, 'ANNOUNCED', ?, 'test', ?)
                """,
                symbol,
                shareQuantity,
                new BigDecimal(issuePrice),
                listingDate,
                LocalDateTime.now()
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
                  theoretical_ex_rights_price, ex_rights_date, payment_date, listing_date, description, created_at
                ) values (?, 'PAID_IN_CAPITAL_INCREASE', ?, ?, 'ANNOUNCED', ?, ?, ?, ?, ?, 'test', ?)
                """,
                symbol,
                shareQuantity,
                new BigDecimal(issuePrice),
                new BigDecimal(basePrice),
                new BigDecimal(theoreticalExRightsPrice),
                exRightsDate,
                paymentDate,
                listingDate,
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
}
