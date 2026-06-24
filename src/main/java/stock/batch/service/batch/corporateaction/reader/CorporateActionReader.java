package stock.batch.service.batch.corporateaction.reader;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ListingActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;
import stock.batch.service.batch.corporateaction.model.StockSplitActionRow;

@Component
@RequiredArgsConstructor
public class CorporateActionReader {

    private final JdbcTemplate jdbcTemplate;

    public List<ExRightsActionRow> findDueExRights(
            LocalDate today,
            String announcedStatus,
            List<String> actionTypes
    ) {
        return jdbcTemplate.query(
                """
                select id, symbol, action_type, theoretical_ex_rights_price, dividend_amount
                  from stock_corporate_action
                 where action_type in (?, ?, ?, ?)
                   and status = ?
                   and ex_rights_date <= ?
                   and theoretical_ex_rights_price is not null
                 order by ex_rights_date asc, id asc
                """,
                (rs, rowNum) -> new ExRightsActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("action_type"),
                        rs.getBigDecimal("theoretical_ex_rights_price"),
                        rs.getBigDecimal("dividend_amount")
                ),
                actionTypes.get(0),
                actionTypes.get(1),
                actionTypes.get(2),
                actionTypes.get(3),
                announcedStatus,
                today
        );
    }

    public List<Long> findDueCashDividendActionIds(LocalDate today, String actionType, String sourceStatus) {
        return jdbcTemplate.query(
                """
                select id
                  from stock_corporate_action
                 where action_type = ?
                   and status = ?
                   and payment_date <= ?
                 order by payment_date asc, id asc
                """,
                (rs, rowNum) -> rs.getLong("id"),
                actionType,
                sourceStatus,
                today
        );
    }

    public List<DividendEntitlementRow> findAnnouncedDividendEntitlements(long actionId, String status) {
        return jdbcTemplate.query(
                """
                select id, account_id, cash_amount
                  from stock_corporate_action_entitlement
                 where action_id = ?
                   and status = ?
                 order by id asc
                """,
                (rs, rowNum) -> new DividendEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getBigDecimal("cash_amount")
                ),
                actionId,
                status
        );
    }

    public List<ListingActionRow> findDueListings(LocalDate today, String actionType, String sourceStatus) {
        return jdbcTemplate.query(
                """
                select id, symbol, share_quantity
                  from stock_corporate_action
                 where action_type = ?
                   and status = ?
                   and listing_date <= ?
                   and share_quantity > 0
                 order by listing_date asc, id asc
                """,
                (rs, rowNum) -> new ListingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ),
                actionType,
                sourceStatus,
                today
        );
    }

    public List<StockSplitActionRow> findDueStockSplits(LocalDate today, String actionType, String status) {
        return jdbcTemplate.query(
                """
                select id, symbol, split_from, split_to
                  from stock_corporate_action
                 where action_type = ?
                   and status = ?
                   and listing_date <= ?
                   and split_from > 0
                   and split_to > split_from
                 order by listing_date asc, id asc
                """,
                (rs, rowNum) -> new StockSplitActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getInt("split_from"),
                        rs.getInt("split_to")
                ),
                actionType,
                status,
                today
        );
    }

    public boolean hasOpenOrderBookOrders(String symbol) {
        Long openOrderCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order
                 where symbol = ?
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                Long.class,
                symbol
        );
        return openOrderCount != null && openOrderCount > 0;
    }

    public List<ShareEntitlementRow> findAnnouncedShareEntitlements(long actionId, String status) {
        return jdbcTemplate.query(
                """
                select id, account_id, symbol, share_quantity
                  from stock_corporate_action_entitlement
                 where action_id = ?
                   and status = ?
                   and share_quantity > 0
                 order by id asc
                """,
                (rs, rowNum) -> new ShareEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ),
                actionId,
                status
        );
    }
}
