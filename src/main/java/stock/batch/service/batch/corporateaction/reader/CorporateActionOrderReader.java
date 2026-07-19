package stock.batch.service.batch.corporateaction.reader;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;

@Component
public class CorporateActionOrderReader {

    private final JdbcClient jdbcClient;

    public CorporateActionOrderReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<DelistingOrderRow> findOpenOrderBookOrderCandidates(String symbol, int limit) {
        return jdbcClient.sql(
                """
                select id,
                       account_id,
                       symbol,
                       side,
                       quantity - filled_quantity as remaining_quantity,
                       reserved_cash
                  from stock_order
                 where symbol = :symbol
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and quantity > filled_quantity
                 order by created_at asc, id asc
                 limit :limit
                """
        )
                .param("symbol", symbol)
                .param("limit", limit)
                .query((rs, rowNum) -> mapDelistingOrderRow(rs))
                .list();
    }

    public void lockAccountsForUpdate(List<DelistingOrderRow> candidates) {
        List<Long> accountIds = candidates.stream()
                .map(DelistingOrderRow::accountId)
                .distinct()
                .sorted()
                .toList();
        if (accountIds.isEmpty()) {
            return;
        }
        jdbcClient.sql(
                        """
                        select id
                          from stock_account
                         where id in (:accountIds)
                         order by id asc
                         for update
                        """
                )
                .param("accountIds", accountIds)
                .query(Long.class)
                .list();
    }

    public void lockSellHoldingsForUpdate(String symbol, List<DelistingOrderRow> candidates) {
        List<Long> accountIds = candidates.stream()
                .filter(order -> "SELL".equals(order.side()))
                .map(DelistingOrderRow::accountId)
                .distinct()
                .sorted()
                .toList();
        if (accountIds.isEmpty()) {
            return;
        }
        jdbcClient.sql(
                        """
                        select id
                          from stock_holding
                         where symbol = :symbol
                           and account_id in (:accountIds)
                         order by account_id asc
                         for update
                        """
                )
                .param("symbol", symbol)
                .param("accountIds", accountIds)
                .query(Long.class)
                .list();
    }

    /**
     * Locks only exact primary keys after account and holding locks are held. Status is evaluated
     * in Java so MySQL cannot choose the open-status range index and gap-lock adjacent inserts.
     */
    public List<DelistingOrderRow> lockOpenOrderBookOrdersForUpdate(List<DelistingOrderRow> candidates) {
        List<Long> orderIds = candidates.stream()
                .map(DelistingOrderRow::id)
                .distinct()
                .sorted()
                .toList();
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                        """
                        select id,
                               account_id,
                               symbol,
                               side,
                               status,
                               quantity - filled_quantity as remaining_quantity,
                               reserved_cash
                          from stock_order
                         where id in (:orderIds)
                         order by id asc
                         for update
                        """
                )
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> new LockedOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getString("status"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ))
                .list()
                .stream()
                .filter(LockedOrderRow::isOpen)
                .map(LockedOrderRow::toDelistingOrderRow)
                .toList();
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
        return Set.copyOf(jdbcClient.sql(
                """
                select distinct symbol
                  from stock_order
                 where symbol in (:symbols)
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """
        )
                .param("symbols", normalizedSymbols)
                .query(String.class)
                .list());
    }

    private DelistingOrderRow mapDelistingOrderRow(ResultSet rs) throws SQLException {
        return new DelistingOrderRow(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getLong("remaining_quantity"),
                rs.getBigDecimal("reserved_cash")
        );
    }

    private record LockedOrderRow(
            long id,
            long accountId,
            String symbol,
            String side,
            String status,
            long remainingQuantity,
            java.math.BigDecimal reservedCash
    ) {

        private boolean isOpen() {
            return ("PENDING".equals(status) || "PARTIALLY_FILLED".equals(status))
                    && remainingQuantity > 0;
        }

        private DelistingOrderRow toDelistingOrderRow() {
            return new DelistingOrderRow(
                    id,
                    accountId,
                    symbol,
                    side,
                    remainingQuantity,
                    reservedCash
            );
        }
    }

}
