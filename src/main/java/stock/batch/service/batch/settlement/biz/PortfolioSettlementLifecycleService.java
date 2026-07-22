package stock.batch.service.batch.settlement.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;

@Service
@RequiredArgsConstructor
public class PortfolioSettlementLifecycleService {

    private final JdbcClient jdbcClient;
    private final PostCloseCycleService postCloseCycleService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final PortfolioSnapshotProcessor portfolioSnapshotProcessor;

    @Transactional
    public PostClosePhaseClaim begin(
            long closeCycleId,
            Long batchJobExecutionId,
            LocalDateTime simulationNow,
            LocalDateTime systemNow
    ) {
        if (marketSessionFenceService.hasOpenMarket()) {
            throw new IllegalStateException(
                    "Portfolio settlement cannot start while any enabled market is open"
            );
        }
        PostCloseCycle cycle = postCloseCycleService.findById(closeCycleId)
                .orElseThrow(() -> new IllegalStateException("Post-close cycle does not exist: " + closeCycleId));
        if (cycle.phase() != PostClosePhase.LEDGER_FROZEN) {
            throw new IllegalStateException(
                    "Portfolio settlement requires LEDGER_FROZEN: cycleId=%d, phase=%s"
                            .formatted(closeCycleId, cycle.phase())
            );
        }
        if (cycle.settlementEligibleAt() != null && simulationNow.isBefore(cycle.settlementEligibleAt())) {
            throw new IllegalStateException(
                    "Portfolio settlement is not eligible yet: eligibleAt=" + cycle.settlementEligibleAt()
            );
        }
        assertFrozenInputs(closeCycleId);
        PostClosePhaseClaim claim = postCloseCycleService.tryClaim(
                        closeCycleId,
                        PostClosePhase.LEDGER_FROZEN,
                        systemNow
                )
                .orElseThrow(() -> new CannotAcquireLockException(
                        "Portfolio settlement phase is already claimed: cycleId=" + closeCycleId
                ));
        postCloseCycleService.linkBatchJobExecution(claim, batchJobExecutionId, systemNow);
        return claim;
    }

    @Transactional
    public void complete(PostClosePhaseClaim claim, LocalDateTime systemNow) {
        PortfolioOutputCounts outputCounts = assertPortfolioOutputs(claim.cycleId());
        persistSettlementMetrics(claim.cycleId(), outputCounts, systemNow);
        postCloseCycleService.completePhase(
                claim,
                PostClosePhase.PORTFOLIO_SETTLED,
                null,
                null,
                systemNow
        );
    }

    @Transactional
    public void fail(PostClosePhaseClaim claim, RuntimeException failure, LocalDateTime systemNow) {
        postCloseCycleService.failPhase(claim, failure, systemNow);
    }

    @Transactional(readOnly = true)
    public boolean isSettlementEligible(long closeCycleId, LocalDateTime simulationNow) {
        return isSettlementEligible(closeCycleId, simulationNow, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public boolean isSettlementEligible(
            long closeCycleId,
            LocalDateTime simulationNow,
            LocalDateTime systemNow
    ) {
        return postCloseCycleService.findById(closeCycleId)
                .filter(cycle -> cycle.phase() == PostClosePhase.LEDGER_FROZEN)
                .filter(cycle -> cycle.settlementEligibleAt() == null
                        || !simulationNow.isBefore(cycle.settlementEligibleAt()))
                .filter(cycle -> postCloseCycleService.isPhaseClaimEligible(cycle, systemNow))
                .isPresent();
    }

    private void assertFrozenInputs(long closeCycleId) {
        FrozenInputIntegrity integrity = jdbcClient.sql(
                        """
                        select metric.captured_open_order_count,
                               metric.cancelled_order_count,
                               metric.settlement_target_account_count,
                               metric.account_snapshot_count,
                               metric.holding_snapshot_count,
                               metric.price_snapshot_count,
                               metric.open_order_summary_count,
                               metric.reconciliation_mismatch_count,
                               (select count(*)
                                  from stock_close_open_order_snapshot
                                 where close_cycle_id = cycle.id) as actual_captured_open_order_count,
                               (select count(*)
                                  from stock_close_open_order_snapshot
                                 where close_cycle_id = cycle.id
                                   and released_at is not null) as actual_released_open_order_count,
                               (select count(*)
                                  from stock_close_account_snapshot
                                 where close_cycle_id = cycle.id
                                   and settlement_target = true) as actual_settlement_target_account_count,
                               (select count(*)
                                  from stock_close_account_snapshot
                                 where close_cycle_id = cycle.id) as actual_account_snapshot_count,
                               (select count(*)
                                  from stock_holding_snapshot
                                 where close_cycle_id = cycle.id) as actual_holding_snapshot_count,
                               (select count(*)
                                  from stock_close_price_snapshot
                                 where close_cycle_id = cycle.id) as actual_price_snapshot_count,
                               (select count(*)
                                  from stock_close_open_order_summary
                                 where close_cycle_id = cycle.id) as actual_open_order_summary_count,
                               (select count(*)
                                  from stock_close_account_snapshot
                                 where close_cycle_id = cycle.id
                                   and reconciliation_status <> 'MATCHED')
                                 + (select count(*)
                                      from stock_close_open_order_summary
                                     where close_cycle_id = cycle.id
                                       and reconciliation_status <> 'MATCHED')
                                 + (select count(*)
                                      from stock_holding_snapshot
                                     where close_cycle_id = cycle.id
                                       and evaluation_price is null)
                                   as actual_reconciliation_mismatch_count
                          from stock_post_close_cycle cycle
                          join stock_market_close_run close_run
                            on close_run.id = cycle.close_run_id
                           and close_run.status = 'COMPLETED'
                          join stock_post_close_cycle_metric metric
                            on metric.close_cycle_id = cycle.id
                           and metric.close_run_id = close_run.id
                         where cycle.id = ?
                        """
                )
                .param(closeCycleId)
                .query((rs, rowNum) -> new FrozenInputIntegrity(
                        rs.getLong("captured_open_order_count"),
                        rs.getLong("cancelled_order_count"),
                        rs.getLong("settlement_target_account_count"),
                        rs.getLong("account_snapshot_count"),
                        rs.getLong("holding_snapshot_count"),
                        rs.getLong("price_snapshot_count"),
                        rs.getLong("open_order_summary_count"),
                        rs.getLong("reconciliation_mismatch_count"),
                        rs.getLong("actual_captured_open_order_count"),
                        rs.getLong("actual_released_open_order_count"),
                        rs.getLong("actual_settlement_target_account_count"),
                        rs.getLong("actual_account_snapshot_count"),
                        rs.getLong("actual_holding_snapshot_count"),
                        rs.getLong("actual_price_snapshot_count"),
                        rs.getLong("actual_open_order_summary_count"),
                        rs.getLong("actual_reconciliation_mismatch_count")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Immutable frozen-input metric or completed close run is missing: cycleId=" + closeCycleId
                ));
        if (!integrity.countsMatch()) {
            throw new IllegalStateException(
                    "Frozen settlement input counts changed after ledger freeze: cycleId=%d, expected=%s, actual=%s"
                            .formatted(closeCycleId, integrity.expectedCounts(), integrity.actualCounts())
            );
        }
        if (integrity.actualReconciliationMismatchCount() > 0) {
            throw new IllegalStateException(
                    "Frozen settlement inputs failed reconciliation: cycleId=%d, mismatches=%d"
                            .formatted(closeCycleId, integrity.actualReconciliationMismatchCount())
            );
        }
        assertAccountHoldingSummaries(closeCycleId);
    }

    /**
     * Reconciles the one-row-per-account position totals captured at close against the immutable
     * holding rows once before settlement starts. Settlement pages then read only the account
     * snapshot index instead of regrouping every holding row for every page.
     */
    private void assertAccountHoldingSummaries(long closeCycleId) {
        Long mismatchCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_close_account_snapshot account_snapshot
                          left join (
                               select account_id,
                                      sum(quantity * coalesce(evaluation_price, average_price)) as market_value,
                                      sum(quantity) as holding_quantity,
                                      sum(reserved_quantity) as reserved_sell_quantity,
                                      sum(case when quantity > 0 then 1 else 0 end) as holding_position_count
                                 from stock_holding_snapshot
                                where close_cycle_id = ?
                                group by account_id
                          ) holding on holding.account_id = account_snapshot.account_id
                         where account_snapshot.close_cycle_id = ?
                           and (
                               account_snapshot.holding_market_value <> coalesce(holding.market_value, 0)
                               or account_snapshot.holding_quantity <> coalesce(holding.holding_quantity, 0)
                               or account_snapshot.reserved_sell_quantity <> coalesce(holding.reserved_sell_quantity, 0)
                               or account_snapshot.holding_position_count <> coalesce(holding.holding_position_count, 0)
                           )
                        """
                )
                .param(closeCycleId)
                .param(closeCycleId)
                .query(Long.class)
                .single();
        if (mismatchCount != null && mismatchCount > 0) {
            throw new IllegalStateException(
                    "Frozen account holding summaries changed after ledger freeze: cycleId=%d, mismatches=%d"
                            .formatted(closeCycleId, mismatchCount)
            );
        }
    }

    private PortfolioOutputCounts assertPortfolioOutputs(long closeCycleId) {
        PortfolioOutputCounts counts = jdbcClient.sql(
                        """
                        select account_snapshot.close_cycle_id,
                               account_snapshot.close_run_id,
                               account_snapshot.account_id,
                               account_snapshot.user_key,
                               account_snapshot.post_cancel_cash,
                               account_snapshot.external_net_cash_flow,
                               account_snapshot.holding_market_value,
                               account_snapshot.subscription_reserved_cash,
                               account_snapshot.holding_quantity,
                               account_snapshot.reserved_sell_quantity,
                               account_snapshot.holding_position_count,
                               portfolio.close_cycle_id as portfolio_cycle_id,
                               portfolio.close_run_id as portfolio_run_id,
                               portfolio.cash_balance as portfolio_cash_balance,
                               portfolio.pending_subscription_asset as portfolio_pending_subscription_asset,
                               portfolio.market_value as portfolio_market_value,
                               portfolio.holding_quantity as portfolio_holding_quantity,
                               portfolio.reserved_sell_quantity as portfolio_reserved_sell_quantity,
                               portfolio.holding_position_count as portfolio_holding_position_count,
                               portfolio.total_asset as portfolio_total_asset,
                               portfolio.net_contribution as portfolio_net_contribution,
                               portfolio.total_profit as portfolio_total_profit,
                               portfolio.return_rate as portfolio_return_rate,
                               portfolio.return_rate_status,
                               portfolio.input_hash,
                               portfolio.calculation_version,
                               portfolio.data_quality_status
                          from stock_close_account_snapshot account_snapshot
                          left join portfolio_snapshot portfolio
                            on portfolio.close_cycle_id = account_snapshot.close_cycle_id
                           and portfolio.account_id = account_snapshot.account_id
                         where account_snapshot.close_cycle_id = ?
                           and account_snapshot.settlement_target = true
                        """
                )
                .param(closeCycleId)
                .query(resultSet -> {
                    long targetCount = 0;
                    long settledCount = 0;
                    while (resultSet.next()) {
                        targetCount++;
                        AccountSettlementTarget target = new AccountSettlementTarget(
                                resultSet.getLong("close_cycle_id"),
                                resultSet.getLong("close_run_id"),
                                resultSet.getLong("account_id"),
                                resultSet.getString("user_key"),
                                resultSet.getBigDecimal("post_cancel_cash"),
                                nullToZero(resultSet.getBigDecimal("external_net_cash_flow")),
                                nullToZero(resultSet.getBigDecimal("holding_market_value")),
                                nullToZero(resultSet.getBigDecimal("subscription_reserved_cash")),
                                resultSet.getLong("holding_quantity"),
                                resultSet.getLong("reserved_sell_quantity"),
                                resultSet.getLong("holding_position_count")
                        );
                        Long portfolioCycleId = resultSet.getObject("portfolio_cycle_id", Long.class);
                        Long portfolioRunId = resultSet.getObject("portfolio_run_id", Long.class);
                        PortfolioSnapshotCommand expected = portfolioSnapshotProcessor.process(target);
                        boolean verified = portfolioCycleId != null
                                && portfolioCycleId == target.closeCycleId()
                                && portfolioRunId != null
                                && portfolioRunId == target.closeRunId()
                                && decimalEquals(expected.cashBalance(), resultSet.getBigDecimal("portfolio_cash_balance"))
                                && decimalEquals(
                                        expected.pendingSubscriptionAsset(),
                                        resultSet.getBigDecimal("portfolio_pending_subscription_asset")
                                )
                                && decimalEquals(expected.marketValue(), resultSet.getBigDecimal("portfolio_market_value"))
                                && longEquals(expected.holdingQuantity(),
                                resultSet.getObject("portfolio_holding_quantity", Long.class))
                                && longEquals(expected.reservedSellQuantity(),
                                resultSet.getObject("portfolio_reserved_sell_quantity", Long.class))
                                && longEquals(expected.holdingPositionCount(),
                                resultSet.getObject("portfolio_holding_position_count", Long.class))
                                && decimalEquals(expected.totalAsset(), resultSet.getBigDecimal("portfolio_total_asset"))
                                && decimalEquals(
                                        expected.netContribution(),
                                        resultSet.getBigDecimal("portfolio_net_contribution")
                                )
                                && decimalEquals(expected.totalProfit(), resultSet.getBigDecimal("portfolio_total_profit"))
                                && decimalEquals(expected.returnRate(), resultSet.getBigDecimal("portfolio_return_rate"))
                                && expected.returnRateStatus().name().equals(resultSet.getString("return_rate_status"))
                                && expected.inputHash().equals(resultSet.getString("input_hash"))
                                && PortfolioSnapshotProcessor.CALCULATION_VERSION.equals(
                                        resultSet.getString("calculation_version")
                                )
                                && "VERIFIED".equals(resultSet.getString("data_quality_status"));
                        if (verified) {
                            settledCount++;
                        }
                    }
                    return new PortfolioOutputCounts(targetCount, settledCount);
                });
        if (counts.missingCount() > 0) {
            throw new IllegalStateException(
                    "Portfolio settlement output is incomplete or does not match frozen inputs: cycleId=%d, invalid=%d"
                            .formatted(closeCycleId, counts.missingCount())
            );
        }
        return counts;
    }

    private boolean decimalEquals(BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return expected.compareTo(actual) == 0;
    }

    private boolean longEquals(long expected, Long actual) {
        return actual != null && expected == actual;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void persistSettlementMetrics(
            long closeCycleId,
            PortfolioOutputCounts counts,
            LocalDateTime updatedAt
    ) {
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle_metric
                           set settlement_target_account_count = ?,
                               settled_account_count = ?,
                               settlement_missing_account_count = ?,
                               updated_at = ?
                         where close_cycle_id = ?
                        """
                )
                .param(counts.targetCount())
                .param(counts.settledCount())
                .param(counts.missingCount())
                .param(updatedAt)
                .param(closeCycleId)
                .update();
        if (updated > 0) {
            return;
        }
        jdbcClient.sql(
                        """
                        insert into stock_post_close_cycle_metric(
                            close_cycle_id,
                            settlement_target_account_count,
                            settled_account_count,
                            settlement_missing_account_count,
                            updated_at
                        )
                        values (?, ?, ?, ?, ?)
                        """
                )
                .param(closeCycleId)
                .param(counts.targetCount())
                .param(counts.settledCount())
                .param(counts.missingCount())
                .param(updatedAt)
                .update();
    }

    private record PortfolioOutputCounts(long targetCount, long settledCount) {

        private long missingCount() {
            return targetCount - settledCount;
        }
    }

    private record FrozenInputIntegrity(
            long capturedOpenOrderCount,
            long cancelledOrderCount,
            long settlementTargetAccountCount,
            long accountSnapshotCount,
            long holdingSnapshotCount,
            long priceSnapshotCount,
            long openOrderSummaryCount,
            long reconciliationMismatchCount,
            long actualCapturedOpenOrderCount,
            long actualReleasedOpenOrderCount,
            long actualSettlementTargetAccountCount,
            long actualAccountSnapshotCount,
            long actualHoldingSnapshotCount,
            long actualPriceSnapshotCount,
            long actualOpenOrderSummaryCount,
            long actualReconciliationMismatchCount
    ) {

        private boolean countsMatch() {
            return capturedOpenOrderCount == actualCapturedOpenOrderCount
                    && cancelledOrderCount == actualReleasedOpenOrderCount
                    && settlementTargetAccountCount == actualSettlementTargetAccountCount
                    && accountSnapshotCount == actualAccountSnapshotCount
                    && holdingSnapshotCount == actualHoldingSnapshotCount
                    && priceSnapshotCount == actualPriceSnapshotCount
                    && openOrderSummaryCount == actualOpenOrderSummaryCount
                    && reconciliationMismatchCount == actualReconciliationMismatchCount;
        }

        private String expectedCounts() {
            return "%d/%d/%d/%d/%d/%d/%d/%d".formatted(
                    capturedOpenOrderCount,
                    cancelledOrderCount,
                    settlementTargetAccountCount,
                    accountSnapshotCount,
                    holdingSnapshotCount,
                    priceSnapshotCount,
                    openOrderSummaryCount,
                    reconciliationMismatchCount
            );
        }

        private String actualCounts() {
            return "%d/%d/%d/%d/%d/%d/%d/%d".formatted(
                    actualCapturedOpenOrderCount,
                    actualReleasedOpenOrderCount,
                    actualSettlementTargetAccountCount,
                    actualAccountSnapshotCount,
                    actualHoldingSnapshotCount,
                    actualPriceSnapshotCount,
                    actualOpenOrderSummaryCount,
                    actualReconciliationMismatchCount
            );
        }
    }
}
