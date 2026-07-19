package stock.batch.service.batch.settlement.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PortfolioSettlementLifecycleServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDateTime CLOSED_AT = BUSINESS_DATE.atTime(18, 0);
    private static final LocalDateTime ELIGIBLE_AT = BUSINESS_DATE.atTime(18, 10);

    @Autowired
    private PortfolioSettlementLifecycleService lifecycleService;

    @Autowired
    private PostCloseCycleService postCloseCycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PortfolioSnapshotProcessor portfolioSnapshotProcessor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("update stock_order_book_market_config set market_status = 'CLOSED'");
        jdbcTemplate.update("update stock_virtual_market_config set market_status = 'CLOSED'");
        jdbcTemplate.update("update stock_market_session_fence set session_state = 'CLOSED'");
        jdbcTemplate.update("delete from portfolio_snapshot");
        jdbcTemplate.update("delete from stock_close_account_snapshot");
        jdbcTemplate.update("delete from stock_post_close_cycle_metric");
        jdbcTemplate.update("delete from stock_close_open_order_summary");
        jdbcTemplate.update("delete from stock_holding_snapshot");
        jdbcTemplate.update("delete from stock_post_close_phase_attempt");
        jdbcTemplate.update("delete from stock_post_close_cycle");
        jdbcTemplate.update("delete from stock_market_close_run");
    }

    @Test
    void begin_enabledMarketStillOpen_rejectsSettlementBeforeFrozenInputScan() {
        PostCloseCycle cycle = frozenCycle();
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                values ('LIFECYCLE', true, 'OPEN', ?)
                """,
                CLOSED_AT
        );

        assertThatThrownBy(() -> lifecycleService.begin(
                cycle.id(),
                104L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("market is open");
    }

    @Test
    void isSettlementEligible_beforeConfiguredTime_returnsFalse() {
        PostCloseCycle cycle = frozenCycle();

        boolean eligible = lifecycleService.isSettlementEligible(cycle.id(), ELIGIBLE_AT.minusNanos(1));

        assertThat(eligible).isFalse();
    }

    @Test
    void isSettlementEligible_failedCycleBeforeRetryWindow_returnsFalseWithoutStartingJob() {
        PostCloseCycle cycle = frozenCycle();
        LocalDateTime retryAt = CLOSED_AT.plusMinutes(2);
        jdbcTemplate.update(
                "update stock_post_close_cycle set status = 'FAILED', next_retry_at = ? where id = ?",
                retryAt,
                cycle.id()
        );

        boolean eligible = lifecycleService.isSettlementEligible(
                cycle.id(),
                ELIGIBLE_AT,
                retryAt.minusNanos(1)
        );

        assertThat(eligible).isFalse();
    }

    @Test
    void begin_mismatchedFrozenAccountSnapshot_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MISMATCHED", true);

        assertThatThrownBy(() -> lifecycleService.begin(
                cycle.id(),
                100L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed reconciliation");
    }

    @Test
    void begin_legacyFrozenCycleWithoutImmutableMetric_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        jdbcTemplate.update(
                "delete from stock_post_close_cycle_metric where close_cycle_id = ?",
                cycle.id()
        );

        assertThatThrownBy(() -> lifecycleService.begin(
                cycle.id(),
                102L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Immutable frozen-input metric");
    }

    @Test
    void begin_snapshotCountChangedAfterFreeze_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", true);
        jdbcTemplate.update(
                "update stock_post_close_cycle_metric set account_snapshot_count = 0 where close_cycle_id = ?",
                cycle.id()
        );

        assertThatThrownBy(() -> lifecycleService.begin(
                cycle.id(),
                103L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("counts changed after ledger freeze");
    }

    @Test
    void begin_accountHoldingSummaryChangedAfterFreeze_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", true);
        jdbcTemplate.update(
                """
                insert into stock_holding_snapshot(
                    close_cycle_id, close_run_id, account_id, symbol, quantity,
                    reserved_quantity, average_price, evaluation_price, snapshot_at
                ) values (?, ?, 99001, 'LIFECYCLE', 2, 1, 100, 120, ?)
                """,
                cycle.id(),
                cycle.closeRunId(),
                CLOSED_AT
        );
        jdbcTemplate.update(
                "update stock_post_close_cycle_metric set holding_snapshot_count = 1 where close_cycle_id = ?",
                cycle.id()
        );

        assertThatThrownBy(() -> lifecycleService.begin(
                cycle.id(),
                105L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holding summaries changed");
    }

    @Test
    void complete_zeroTargetFrozenCohort_advancesToPortfolioSettled() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", false);
        PostClosePhaseClaim claim = lifecycleService.begin(
                cycle.id(),
                101L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        );

        lifecycleService.complete(claim, CLOSED_AT.plusSeconds(2));

        assertThat(postCloseCycleService.findById(cycle.id()).orElseThrow().phase())
                .isEqualTo(PostClosePhase.PORTFOLIO_SETTLED);
        assertThat(jdbcTemplate.queryForObject(
                "select settlement_missing_account_count from stock_post_close_cycle_metric where close_cycle_id = ?",
                Long.class,
                cycle.id()
        )).isZero();
    }

    @Test
    void complete_portfolioHashDoesNotMatchFrozenInputs_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", true);
        PostClosePhaseClaim claim = lifecycleService.begin(
                cycle.id(),
                106L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        );
        insertPortfolioSnapshot(cycle, "0".repeat(64), BigDecimal.valueOf(1_000_000));

        assertThatThrownBy(() -> lifecycleService.complete(claim, CLOSED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match frozen inputs");
    }

    @Test
    void complete_portfolioTotalDoesNotMatchFrozenFormula_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", true);
        PostClosePhaseClaim claim = lifecycleService.begin(
                cycle.id(),
                107L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        );
        insertPortfolioSnapshot(
                cycle,
                portfolioSnapshotProcessor.inputHash(settlementTarget(cycle)),
                BigDecimal.valueOf(999_999)
        );

        assertThatThrownBy(() -> lifecycleService.complete(claim, CLOSED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match frozen inputs");
    }

    @Test
    void complete_portfolioHoldingMetricsDoNotMatchFrozenInputs_rejectsSettlement() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", true);
        PostClosePhaseClaim claim = lifecycleService.begin(
                cycle.id(),
                108L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        );
        insertPortfolioSnapshot(
                cycle,
                portfolioSnapshotProcessor.inputHash(settlementTarget(cycle)),
                BigDecimal.valueOf(1_000_000)
        );
        jdbcTemplate.update(
                "update portfolio_snapshot set holding_position_count = 1 where close_cycle_id = ?",
                cycle.id()
        );

        assertThatThrownBy(() -> lifecycleService.complete(claim, CLOSED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match frozen inputs");
    }

    @Test
    void complete_portfolioMatchesFrozenFormula_advancesToPortfolioSettled() {
        PostCloseCycle cycle = frozenCycle();
        insertAccountSnapshot(cycle, "MATCHED", true);
        PostClosePhaseClaim claim = lifecycleService.begin(
                cycle.id(),
                109L,
                ELIGIBLE_AT,
                CLOSED_AT.plusSeconds(1)
        );
        insertPortfolioSnapshot(
                cycle,
                portfolioSnapshotProcessor.inputHash(settlementTarget(cycle)),
                new BigDecimal("1000000.0000")
        );

        lifecycleService.complete(claim, CLOSED_AT.plusSeconds(2));

        assertThat(postCloseCycleService.findById(cycle.id()).orElseThrow().phase())
                .isEqualTo(PostClosePhase.PORTFOLIO_SETTLED);
    }

    private PostCloseCycle frozenCycle() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(BUSINESS_DATE, CLOSED_AT);
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(
                    symbol, business_date, closed_at, status, cancelled_order_count,
                    holding_snapshot_count, price_rollover_count, created_at, completed_at
                ) values (null, ?, ?, 'COMPLETED', 0, 0, 0, ?, ?)
                """,
                BUSINESS_DATE,
                CLOSED_AT,
                CLOSED_AT,
                CLOSED_AT
        );
        Long closeRunId = jdbcTemplate.queryForObject("select max(id) from stock_market_close_run", Long.class);
        jdbcTemplate.update(
                """
                update stock_post_close_cycle
                   set phase = 'LEDGER_FROZEN',
                       status = 'PENDING',
                       close_run_id = ?,
                       settlement_eligible_at = ?,
                       updated_at = ?
                 where id = ?
                """,
                closeRunId,
                ELIGIBLE_AT,
                CLOSED_AT,
                cycle.id()
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle_metric(
                    close_cycle_id, close_run_id, updated_at
                ) values (?, ?, ?)
                """,
                cycle.id(),
                closeRunId,
                CLOSED_AT
        );
        return postCloseCycleService.findById(cycle.id()).orElseThrow();
    }

    private void insertAccountSnapshot(PostCloseCycle cycle, String reconciliationStatus, boolean settlementTarget) {
        jdbcTemplate.update(
                """
                insert into stock_close_account_snapshot(
                    close_cycle_id, close_run_id, account_id, user_key, account_status,
                    settlement_target, pre_cancel_cash, pre_cancel_order_reserved_cash,
                    subscription_reserved_cash, post_cancel_cash, external_net_cash_flow,
                    cash_flow_watermark_id, reconciliation_status, snapshot_at, created_at
                ) values (?, ?, 99001, 'lifecycle-test', 'ACTIVE', ?, ?, 0, 0, ?, 0, 0, ?, ?, ?)
                """,
                cycle.id(),
                cycle.closeRunId(),
                settlementTarget,
                BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(1_000_000),
                reconciliationStatus,
                CLOSED_AT,
                CLOSED_AT
        );
        jdbcTemplate.update(
                """
                update stock_post_close_cycle_metric
                   set account_snapshot_count = account_snapshot_count + 1,
                       settlement_target_account_count = settlement_target_account_count + ?,
                       settlement_missing_account_count = settlement_missing_account_count + ?,
                       reconciliation_mismatch_count = reconciliation_mismatch_count + ?
                 where close_cycle_id = ?
                """,
                settlementTarget ? 1 : 0,
                settlementTarget ? 1 : 0,
                "MATCHED".equals(reconciliationStatus) ? 0 : 1,
                cycle.id()
        );
    }

    private void insertPortfolioSnapshot(
            PostCloseCycle cycle,
            String inputHash,
            BigDecimal totalAsset
    ) {
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    close_cycle_id, close_run_id, account_id, snapshot_date,
                    total_asset, cash_balance, market_value,
                    holding_quantity, reserved_sell_quantity, holding_position_count,
                    return_rate, input_hash, calculation_version, data_quality_status,
                    source_build_version, created_at
                ) values (?, ?, 99001, ?, ?, 1000000, 0, 0, 0, 0,
                          0, ?, ?, 'VERIFIED', 'test', ?)
                """,
                cycle.id(),
                cycle.closeRunId(),
                BUSINESS_DATE,
                totalAsset,
                inputHash,
                PortfolioSnapshotProcessor.CALCULATION_VERSION,
                CLOSED_AT
        );
    }

    private AccountSettlementTarget settlementTarget(PostCloseCycle cycle) {
        return new AccountSettlementTarget(
                cycle.id(),
                cycle.closeRunId(),
                99001L,
                "lifecycle-test",
                BigDecimal.valueOf(1_000_000),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0
        );
    }
}
