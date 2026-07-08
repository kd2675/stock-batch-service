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
     * Completion criteria: full market-close rollover and portfolio settlement must both be
     * present for the same simulation business date. This is the gate for opening the next
     * regular session or advancing the simulation clock.
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
     * Listing supply accounts are excluded because they are operational inventory accounts, not
     * participant portfolios. When no eligible accounts exist, settlement has no remaining work.
     */
    public boolean isPortfolioSettlementComplete(LocalDate businessDate) {
        Long eligibleAccountCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_account
                         where status = 'ACTIVE'
                           and user_key is not null
                           and user_key not like 'stock-listing-%'
                        """
                )
                .query(Long.class)
                .single();
        if (eligibleAccountCount == null || eligibleAccountCount == 0) {
            return true;
        }
        Long snapshotAccountCount = jdbcClient.sql(
                        """
                        select count(distinct ps.account_id)
                          from portfolio_snapshot ps
                          join stock_account a on a.id = ps.account_id
                         where ps.snapshot_date = ?
                           and a.status = 'ACTIVE'
                           and a.user_key is not null
                           and a.user_key not like 'stock-listing-%'
                        """
                )
                .param(businessDate)
                .query(Long.class)
                .single();
        return snapshotAccountCount != null && snapshotAccountCount >= eligibleAccountCount;
    }
}
