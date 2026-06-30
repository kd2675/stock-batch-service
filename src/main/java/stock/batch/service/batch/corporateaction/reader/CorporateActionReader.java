package stock.batch.service.batch.corporateaction.reader;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.DelistingActionRow;
import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;
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

    public List<DelistingActionRow> findDueDelistings(LocalDate today, String actionType, String status) {
        return jdbcTemplate.query(
                """
                select id, symbol
                  from stock_corporate_action
                 where action_type = ?
                   and status = ?
                   and delisting_date <= ?
                   and delisting_treatment = 'ZERO_VALUE'
                 order by delisting_date asc, id asc
                """,
                (rs, rowNum) -> new DelistingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol")
                ),
                actionType,
                status,
                today
        );
    }

    public List<DelistingOrderRow> findOpenOrderBookOrdersForUpdate(String symbol) {
        return jdbcTemplate.query(
                """
                select id,
                       account_id,
                       symbol,
                       side,
                       quantity - filled_quantity as remaining_quantity,
                       reserved_cash
                  from stock_order
                 where symbol = ?
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                 order by created_at asc, id asc
                 for update
                """,
                (rs, rowNum) -> new DelistingOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ),
                symbol
        );
    }

    public Set<String> findSymbolsWithOpenOrderBookOrders(List<String> symbols) {
        List<String> normalizedSymbols = symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (normalizedSymbols.isEmpty()) {
            return Set.of();
        }
        String placeholders = normalizedSymbols.stream()
                .map(symbol -> "?")
                .collect(Collectors.joining(", "));
        return Set.copyOf(jdbcTemplate.queryForList(
                """
                select distinct symbol
                  from stock_order
                 where symbol in (%s)
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """.formatted(placeholders),
                String.class,
                normalizedSymbols.toArray()
        ));
    }

    public Optional<Long> findLatestCompletedMarketCloseRunId(String symbol) {
        List<Long> runIds = jdbcTemplate.query(
                """
                select id
                  from stock_market_close_run
                 where status = 'COMPLETED'
                   and (symbol = ? or symbol is null)
                 order by closed_at desc, id desc
                 limit 1
                """,
                (rs, rowNum) -> rs.getLong("id"),
                symbol
        );
        return runIds.stream().findFirst();
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
