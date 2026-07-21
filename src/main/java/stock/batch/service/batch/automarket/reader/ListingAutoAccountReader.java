package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;

@Component
public class ListingAutoAccountReader {

    private final JdbcClient jdbcClient;

    public ListingAutoAccountReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public List<ListingAutoAccountConfig> findEnabledListingAutoAccountConfigs(AutoMarketConfig config) {
        return jdbcClient.sql(
                """
                select c.symbol,
                       i.market,
                       a.id as account_id,
                       c.user_key,
                       c.position_side,
                       c.operation_mode,
                       c.strategy_profile,
                       c.initial_inventory_quantity,
                       c.initial_issue_price,
                       c.max_order_quantity,
                       c.order_ttl_seconds,
                       c.price_offset_ticks,
                       c.target_spread_ticks,
                       c.inventory_skew_ticks,
                       c.minimum_profit_rate,
                       c.aggressive_unwind_threshold,
                       c.aggressive_order_ratio,
                       c.target_buy_quantity,
                       c.target_sell_quantity,
                       c.target_holding_quantity,
                       c.inventory_band_quantity,
                       i.tick_size,
                       i.price_limit_rate,
                       p.current_price,
                       p.previous_close
                from stock_listing_auto_account_config c
                join stock_account a on a.user_key = c.user_key and a.status = 'ACTIVE'
                join stock_order_book_instrument i on i.symbol = c.symbol and i.enabled = true
                join stock_order_book_market_config m on m.symbol = c.symbol and m.enabled = true and m.market_status = 'OPEN'
                join stock_price p on p.symbol = c.symbol
                where c.enabled = true
                  and c.symbol = ?
                order by c.symbol asc
                """
        )
                .param(config.symbol())
                .query((rs, rowNum) -> AutoMarketReaderMapper.toListingAutoAccountConfig(rs))
                .list();
    }

    public long getAvailableQuantity(long accountId, String symbol) {
        Long quantity = jdbcClient.sql(
                """
                select coalesce(max(quantity - reserved_quantity), 0)
                from stock_holding
                where account_id = ?
                  and symbol = ?
                """
        )
                .params(accountId, symbol)
                .query(Long.class)
                .single();
        return quantity == null ? 0L : quantity;
    }

    public long getHoldingQuantity(long accountId, String symbol) {
        Long quantity = jdbcClient.sql(
                """
                select coalesce(max(quantity), 0)
                  from stock_holding
                 where account_id = ?
                   and symbol = ?
                """
        )
                .params(accountId, symbol)
                .query(Long.class)
                .single();
        return quantity == null ? 0L : Math.max(0L, quantity);
    }

    public BigDecimal getCashBalance(long accountId) {
        BigDecimal cash = jdbcClient.sql("select coalesce(max(cash_balance), 0) from stock_account where id = ?")
                .param(accountId)
                .query(BigDecimal.class)
                .single();
        return cash == null ? BigDecimal.ZERO : cash;
    }

    public ListingAutoInventoryState getInventoryState(long accountId, String symbol) {
        return jdbcClient.sql(
                """
                select coalesce(a.cash_balance, 0) as cash_balance,
                       coalesce(h.quantity, 0) as holding_quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       coalesce(h.average_price, 0) as average_price
                  from stock_account a
                  left join stock_holding h on h.account_id = a.id and h.symbol = ?
                 where a.id = ?
                """
        )
                .params(symbol, accountId)
                .query((rs, rowNum) -> new ListingAutoInventoryState(
                        rs.getBigDecimal("cash_balance"),
                        Math.max(0L, rs.getLong("holding_quantity")),
                        Math.max(0L, rs.getLong("reserved_quantity")),
                        rs.getBigDecimal("average_price")
                ))
                .optional()
                .orElse(ListingAutoInventoryState.EMPTY);
    }

    public record ListingAutoInventoryState(
            BigDecimal cashBalance,
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
        private static final ListingAutoInventoryState EMPTY = new ListingAutoInventoryState(
                BigDecimal.ZERO, 0L, 0L, BigDecimal.ZERO
        );

        public long availableQuantity() {
            return Math.max(0L, holdingQuantity - reservedQuantity);
        }
    }
}
