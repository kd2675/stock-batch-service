package stock.batch.service.marketclose.biz;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketClosePostProcessingCompletionService {

    private final JdbcClient jdbcClient;

    /**
     * Early post-close completion criteria: full market-close rollover and portfolio settlement
     * must both be present for the same simulation business date. This permits advancing to the
     * next 00:00 maintenance window; opening the next regular session additionally requires the
     * cycle to reach READY_TO_OPEN.
     */
    public boolean isComplete(LocalDate businessDate) {
        if (businessDate == null || !hasCompletedFullCloseRun(businessDate)) {
            return false;
        }
        return isPortfolioSettlementComplete(businessDate);
    }

    /**
     * A full close run has symbol = null. Symbol-scoped close runs are maintenance paths and
     * must not unlock next-day processing by themselves.
     */
    public boolean hasCompletedFullCloseRun(LocalDate businessDate) {
        Long completedRunCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_market_close_run
                         where business_date = ?
                           and symbol is null
                           and status = 'COMPLETED'
                        """
                )
                .param(businessDate)
                .query(Long.class)
                .single();
        return completedRunCount != null && completedRunCount > 0;
    }

    /**
     * Completion is based on the account cohort frozen by the close cycle. Accounts created,
     * detached, or reactivated after close must never change a past settlement result. The
     * settlement completion transaction already reconciles every frozen account; polling reads
     * its O(1) metric row instead of rescanning the cohort every ten seconds.
     */
    public boolean isPortfolioSettlementComplete(LocalDate businessDate) {
        Long settledCycleCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_post_close_cycle cycle
                          left join stock_post_close_cycle_metric metric
                            on metric.close_cycle_id = cycle.id
                         where cycle.business_date = ?
                           and cycle.scope_type = 'FULL_MARKET'
                           and cycle.scope_key = 'ALL'
                           and cycle.phase in (
                               'PORTFOLIO_SETTLED', 'OVERNIGHT_CASH_APPLIED', 'CORPORATE_CASH_APPLIED',
                               'REPORTS_AGGREGATED', 'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                               'MARKET_DATA_PREPARED', 'AUTO_MARKET_PREPARED', 'READY_TO_OPEN', 'COMPLETED'
                           )
                           and (
                               (
                                   metric.close_cycle_id is not null
                                   and metric.settlement_missing_account_count = 0
                                   and metric.settlement_target_account_count = metric.settled_account_count
                               )
                               or (
                                   metric.close_cycle_id is null
                                   and cycle.phase = 'COMPLETED'
                                   and cycle.status = 'COMPLETED'
                                   and cycle.build_version is null
                                   and cycle.schema_version is null
                               )
                           )
                        """
                )
                .param(businessDate)
                .query(Long.class)
                .single();
        return settledCycleCount != null && settledCycleCount == 1L;
    }
}
