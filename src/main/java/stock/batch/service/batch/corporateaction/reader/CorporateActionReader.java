package stock.batch.service.batch.corporateaction.reader;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.DelistingActionRow;
import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ListingActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;
import stock.batch.service.batch.corporateaction.model.StockSplitActionRow;

@Component
public class CorporateActionReader {

    private final JdbcClient jdbcClient;

    public CorporateActionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<ExRightsActionRow> findDueExRights(
            LocalDate today,
            String announcedStatus,
            List<String> actionTypes
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, action_type, theoretical_ex_rights_price, dividend_amount
                  from stock_corporate_action
                 where action_type in (:actionTypes)
                   and status = :announcedStatus
                   and ex_rights_date <= :today
                   and theoretical_ex_rights_price is not null
                 order by ex_rights_date asc, id asc
                """
        )
                .param("actionTypes", actionTypes)
                .param("announcedStatus", announcedStatus)
                .param("today", today)
                .query((rs, rowNum) -> new ExRightsActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("action_type"),
                        rs.getBigDecimal("theoretical_ex_rights_price"),
                        rs.getBigDecimal("dividend_amount")
                ))
                .list();
    }

    public List<Long> findDueCashDividendActionIds(LocalDate today, String actionType, String sourceStatus) {
        return jdbcClient.sql(
                """
                select id
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :sourceStatus
                   and payment_date <= :today
                 order by payment_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .param("today", today)
                .query((rs, rowNum) -> rs.getLong("id"))
                .list();
    }

    public List<DividendEntitlementRow> findAnnouncedDividendEntitlements(long actionId, String status) {
        return jdbcClient.sql(
                """
                select id, account_id, cash_amount
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                 order by account_id asc, id asc
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .query((rs, rowNum) -> new DividendEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getBigDecimal("cash_amount")
                ))
                .list();
    }

    public List<ListingActionRow> findDueListings(LocalDate today, String actionType, String sourceStatus) {
        return jdbcClient.sql(
                """
                select id, symbol, share_quantity
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :sourceStatus
                   and listing_date <= :today
                   and share_quantity > 0
                 order by listing_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .param("today", today)
                .query((rs, rowNum) -> new ListingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ))
                .list();
    }

    public List<StockSplitActionRow> findDueStockSplits(LocalDate today, String actionType, String status) {
        return jdbcClient.sql(
                """
                select id, symbol, split_from, split_to
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :status
                   and listing_date <= :today
                   and split_from > 0
                   and split_to > split_from
                 order by listing_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("status", status)
                .param("today", today)
                .query((rs, rowNum) -> new StockSplitActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getInt("split_from"),
                        rs.getInt("split_to")
                ))
                .list();
    }

    public List<DelistingActionRow> findDueDelistings(LocalDate today, String actionType, String status) {
        return jdbcClient.sql(
                """
                select id, symbol
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :status
                   and delisting_date <= :today
                   and delisting_treatment = 'ZERO_VALUE'
                 order by delisting_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("status", status)
                .param("today", today)
                .query((rs, rowNum) -> new DelistingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol")
                ))
                .list();
    }

    public Optional<Long> findLatestCompletedMarketCloseRunId(String symbol) {
        return jdbcClient.sql(
                """
                select id
                  from stock_market_close_run
                 where status = 'COMPLETED'
                   and (symbol = :symbol or symbol is null)
                 order by closed_at desc, id desc
                 limit 1
                """
        )
                .param("symbol", symbol)
                .query(Long.class)
                .optional();
    }

    public boolean existsCompletedMarketCloseRun(LocalDate businessDate) {
        Long count = jdbcClient.sql(
                """
                select count(*)
                  from stock_market_close_run
                 where status = 'COMPLETED'
                   and business_date = :businessDate
                """
        )
                .param("businessDate", businessDate)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public List<ShareEntitlementRow> findAnnouncedShareEntitlements(long actionId, String status) {
        return jdbcClient.sql(
                """
                select id, account_id, symbol, share_quantity
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                   and share_quantity > 0
                 order by account_id asc, symbol asc, id asc
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .query((rs, rowNum) -> new ShareEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ))
                .list();
    }
}
