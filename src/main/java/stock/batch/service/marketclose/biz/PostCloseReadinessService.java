package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.batch.corporateaction.reader.CorporateActionReader;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;

@Service
@RequiredArgsConstructor
public class PostCloseReadinessService {

    private static final int MESSAGE_LIMIT = 500;

    private final JdbcClient jdbcClient;
    private final PostCloseCycleService postCloseCycleService;
    private final CorporateActionReader corporateActionReader;
    private final AutoMarketProfileQueueReconcileService profileQueueReconcileService;

    /**
     * Readiness runs once per close cycle and persists at most ten control rows. The independent
     * transaction is intentional: a failed readiness Step must retain its bounded diagnostics even
     * though Spring Batch marks the outer resourceless tasklet as failed. No order, execution, or
     * holding ledger is queried here.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = ReadinessValidationException.class
    )
    public int validateReadyToOpen(long closeCycleId, LocalDate preparingBusinessDate) {
        PostCloseCycle cycle = postCloseCycleService.findById(closeCycleId)
                .orElseThrow(() -> new IllegalStateException(
                        "Post-close cycle does not exist: " + closeCycleId
                ));
        validateCycle(cycle, closeCycleId, preparingBusinessDate);

        BoundedReadinessCounts counts = readBoundedCounts(
                cycle,
                closeCycleId,
                preparingBusinessDate
        );
        long corporateCashFailures = corporateActionReader.countIncompleteCorporateCashActions(
                cycle.businessDate()
        );
        long corporateTransformFailures = corporateActionReader.countIncompletePreOpenSecurityTransforms(
                preparingBusinessDate
        );
        QueueCheck queueCheck = checkProfileQueue();
        long identityFailures = postCloseCycleService.matchesRuntimeIdentity(closeCycleId) ? 0L : 1L;

        LocalDateTime checkedAt = LocalDateTime.now();
        List<ReadinessCheckResult> checks = List.of(
                result("PORTFOLIO_SETTLEMENT", counts.settlementFailures(),
                        "Frozen settlement cohort is complete"),
                result("BUSINESS_STATE", counts.businessStateFailures(),
                        "Active and preparing business dates are aligned"),
                result("MARKET_CLOSED", counts.marketOpenFailures(),
                        "All enabled markets are closed"),
                result("SESSION_FENCE_PREPARING", counts.fenceFailures(),
                        "Enabled symbol fences are PREPARING for the next business date"),
                result("PRICE_SNAPSHOT", counts.priceFailures(),
                        "Close price and order-book daily snapshots are complete"),
                result("CORPORATE_CASH", corporateCashFailures,
                        "Corporate cash actions are complete"),
                result("CORPORATE_TRANSFORMS", corporateTransformFailures,
                        "Pre-open corporate security transforms are complete"),
                result("AUTO_MARKET_REGIME", counts.regimeFailures(),
                        "Daily auto-market regimes are prepared"),
                new ReadinessCheckResult(
                        "AUTO_MARKET_PROFILE_QUEUE",
                        queueCheck.failureCount() == 0 ? "PASSED" : "FAILED",
                        queueCheck.failureCount(),
                        truncate(queueCheck.message())
                ),
                result("RUNTIME_IDENTITY", identityFailures,
                        "Cycle build and schema versions match the running service")
        );
        replaceReadinessChecks(closeCycleId, checks, checkedAt);

        long failureCount = checks.stream().mapToLong(ReadinessCheckResult::failureCount).sum();
        if (failureCount > 0) {
            String failedChecks = checks.stream()
                    .filter(check -> check.failureCount() > 0)
                    .map(check -> check.checkCode() + "=" + check.failureCount())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("UNKNOWN=1");
            throw new ReadinessValidationException(
                    "Market-open readiness validation failed: cycleId=%d, preparingDate=%s, failures=%d, checks=%s"
                            .formatted(closeCycleId, preparingBusinessDate, failureCount, failedChecks)
            );
        }
        return checks.size();
    }

    private void validateCycle(
            PostCloseCycle cycle,
            long closeCycleId,
            LocalDate preparingBusinessDate
    ) {
        if (cycle.scopeType() != PostCloseScopeType.FULL_MARKET || !"ALL".equals(cycle.scopeKey())) {
            throw new IllegalStateException("Market-open readiness requires a full-market cycle");
        }
        if (cycle.phase() != PostClosePhase.AUTO_MARKET_PREPARED) {
            throw new IllegalStateException(
                    "Market-open readiness requires AUTO_MARKET_PREPARED: cycleId=%d, phase=%s"
                            .formatted(closeCycleId, cycle.phase())
            );
        }
        if (preparingBusinessDate == null
                || !cycle.businessDate().plusDays(1).equals(preparingBusinessDate)) {
            throw new IllegalStateException(
                    "Preparing business date is not the next cycle date: closeDate=%s, preparingDate=%s"
                            .formatted(cycle.businessDate(), preparingBusinessDate)
            );
        }
    }

    private BoundedReadinessCounts readBoundedCounts(
            PostCloseCycle cycle,
            long closeCycleId,
            LocalDate preparingBusinessDate
    ) {
        return jdbcClient.sql(
                        """
                        select (
                            select case when exists (
                                select 1
                                  from stock_market_business_state state
                                 where state.state_id = 'DEFAULT'
                                   and state.active_business_date = ?
                                   and state.preparing_business_date = ?
                            ) then 0 else 1 end
                        ) as business_state_failures,
                        (
                            select count(*)
                              from stock_order_book_market_config config
                             where config.enabled = true
                               and config.market_status = 'OPEN'
                        ) + (
                            select count(*)
                              from stock_virtual_market_config config
                             where config.enabled = true
                               and config.market_status = 'OPEN'
                        ) as market_open_failures,
                        (
                            select count(*)
                              from stock_order_book_market_config config
                              left join stock_market_session_fence fence
                                on fence.market_type = 'ORDER_BOOK'
                               and fence.symbol = config.symbol
                             where config.enabled = true
                               and (
                                   fence.symbol is null
                                   or fence.business_date <> ?
                                   or fence.session_state <> 'PREPARING'
                               )
                        ) + (
                            select count(*)
                              from stock_virtual_market_config config
                              left join stock_market_session_fence fence
                                on fence.market_type = 'VIRTUAL_PRICE'
                               and fence.symbol = config.symbol
                             where config.enabled = true
                               and (
                                   fence.symbol is null
                                   or fence.business_date <> ?
                                   or fence.session_state <> 'PREPARING'
                               )
                        ) as fence_failures,
                        (
                            select count(*)
                              from stock_order_book_market_config config
                             where config.enabled = true
                               and not exists (
                                   select 1
                                     from stock_close_price_snapshot price_snapshot
                                    where price_snapshot.close_cycle_id = ?
                                      and price_snapshot.symbol = config.symbol
                                      and price_snapshot.order_book_symbol = true
                               )
                        ) + (
                            select count(*)
                              from stock_close_price_snapshot price_snapshot
                             where price_snapshot.close_cycle_id = ?
                               and price_snapshot.order_book_symbol = true
                               and not exists (
                                   select 1
                                     from stock_order_book_daily_snapshot daily_snapshot
                                    where daily_snapshot.close_run_id = price_snapshot.close_run_id
                                      and daily_snapshot.symbol = price_snapshot.symbol
                               )
                        ) as price_failures,
                        (
                            select count(*)
                              from stock_auto_market_config config
                              join stock_order_book_instrument instrument
                                on instrument.symbol = config.symbol
                               and instrument.enabled = true
                             where config.enabled = true
                               and not exists (
                                   select 1
                                     from stock_order_book_daily_regime regime
                                    where regime.symbol = config.symbol
                                      and regime.simulation_trade_date = ?
                                      and regime.regime_phase = 'SLOT_0600'
                               )
                        ) as regime_failures,
                        (
                            select case when exists (
                                select 1
                                  from stock_post_close_cycle_metric metric
                                 where metric.close_cycle_id = ?
                                   and metric.reconciliation_mismatch_count = 0
                                   and metric.settlement_missing_account_count = 0
                                   and metric.settlement_target_account_count = metric.settled_account_count
                            ) then 0 else 1 end
                        ) as settlement_failures
                        """
                )
                .param(cycle.businessDate())
                .param(preparingBusinessDate)
                .param(preparingBusinessDate)
                .param(preparingBusinessDate)
                .param(closeCycleId)
                .param(closeCycleId)
                .param(preparingBusinessDate)
                .param(closeCycleId)
                .query((rs, rowNum) -> new BoundedReadinessCounts(
                        rs.getLong("business_state_failures"),
                        rs.getLong("market_open_failures"),
                        rs.getLong("fence_failures"),
                        rs.getLong("price_failures"),
                        rs.getLong("regime_failures"),
                        rs.getLong("settlement_failures")
                ))
                .single();
    }

    private QueueCheck checkProfileQueue() {
        try {
            boolean synchronizedQueue = profileQueueReconcileService.isPreOpenQueueSynchronized();
            return synchronizedQueue
                    ? new QueueCheck(0L, "Auto-market profile queue matches the bounded DB schedule projection")
                    : new QueueCheck(1L, "Auto-market profile queue differs from the bounded DB schedule projection");
        } catch (RuntimeException failure) {
            return new QueueCheck(
                    1L,
                    "Auto-market profile queue comparison failed: " + failure.getMessage()
            );
        }
    }

    private ReadinessCheckResult result(String checkCode, long failureCount, String successMessage) {
        return new ReadinessCheckResult(
                checkCode,
                failureCount == 0 ? "PASSED" : "FAILED",
                Math.max(0L, failureCount),
                failureCount == 0
                        ? successMessage
                        : successMessage + "; incomplete or mismatched items=" + failureCount
        );
    }

    private void replaceReadinessChecks(
            long closeCycleId,
            List<ReadinessCheckResult> checks,
            LocalDateTime checkedAt
    ) {
        jdbcClient.sql("delete from stock_post_close_readiness_check where close_cycle_id = ?")
                .param(closeCycleId)
                .update();
        for (int index = 0; index < checks.size(); index++) {
            ReadinessCheckResult check = checks.get(index);
            jdbcClient.sql(
                            """
                            insert into stock_post_close_readiness_check(
                                close_cycle_id, check_code, display_order, check_status,
                                failure_count, message, checked_at
                            ) values (?, ?, ?, ?, ?, ?, ?)
                            """
                    )
                    .param(closeCycleId)
                    .param(check.checkCode())
                    .param(index + 1)
                    .param(check.status())
                    .param(check.failureCount())
                    .param(truncate(check.message()))
                    .param(checkedAt)
                    .update();
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MESSAGE_LIMIT) {
            return value;
        }
        return value.substring(0, MESSAGE_LIMIT);
    }

    private record BoundedReadinessCounts(
            long businessStateFailures,
            long marketOpenFailures,
            long fenceFailures,
            long priceFailures,
            long regimeFailures,
            long settlementFailures
    ) {
    }

    private record QueueCheck(long failureCount, String message) {
    }

    private record ReadinessCheckResult(
            String checkCode,
            String status,
            long failureCount,
            String message
    ) {
    }

    private static final class ReadinessValidationException extends IllegalStateException {

        private ReadinessValidationException(String message) {
            super(message);
        }
    }
}
