package stock.batch.service.batch.marketclose.writer;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;

@Component
public class MarketCloseRolloverWriter {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;

    public MarketCloseRolloverWriter(
            JdbcTemplate jdbcTemplate,
            StockHoldingReservationJdbcSupport holdingReservationJdbcSupport
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.holdingReservationJdbcSupport = holdingReservationJdbcSupport;
    }

    public long createCloseRun(String symbol, LocalDate businessDate, LocalDateTime closedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into stock_market_close_run(
                        symbol, business_date, closed_at, status,
                        cancelled_order_count, holding_snapshot_count, price_rollover_count,
                        created_at, completed_at
                    )
                    values (?, ?, ?, 'STARTED', 0, 0, 0, ?, null)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, symbol);
            ps.setObject(2, businessDate);
            ps.setObject(3, closedAt);
            ps.setObject(4, closedAt);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Market close run id was not generated");
        }
        return key.longValue();
    }

    public boolean hasCompletedFullCloseRun(LocalDate businessDate) {
        Integer count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_market_close_run
                         where business_date = ?
                           and symbol is null
                           and status = 'COMPLETED'
                        """
                )
                .param(businessDate)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public List<String> findCloseLockSymbols(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            return List.of(symbol);
        }
        return jdbcClient.sql(
                """
                select symbol
                  from (
                       select symbol
                         from stock_order_book_market_config
                        where enabled = true
                       union
                       select symbol
                         from stock_order_book_instrument
                        where enabled = true
                       union
                       select symbol
                         from stock_price
                       union
                       select symbol
                         from stock_order
                        where market_type = 'ORDER_BOOK'
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                       union
                       select symbol
                         from stock_holding
                        where quantity > 0 or reserved_quantity > 0
                  ) close_symbols
                 where symbol is not null
                 order by symbol asc
                """
        )
                .query(String.class)
                .list();
    }

    public List<MarketCloseOrderRow> findOpenOrderBookOrdersForUpdate(String symbol) {
        return jdbcClient.sql(
                """
                select id,
                       account_id,
                       symbol,
                       side,
                       quantity - filled_quantity as remaining_quantity,
                       reserved_cash
                  from stock_order
                 where market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
	                   and (? is null or symbol = ?)
	                 order by symbol asc, created_at asc, id asc
	                 for update
	                """
        )
                .param(symbol)
                .param(symbol)
                .query((rs, rowNum) -> new MarketCloseOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ))
                .list();
    }

    public void creditCash(long accountId, BigDecimal cashAmount, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + ?,
                       updated_at = ?
                 where id = ?
                """,
                cashAmount,
                updatedAt,
                accountId
        );
    }

    public void releaseReservedSellQuantity(long accountId, String symbol, long quantity, LocalDateTime updatedAt) {
        holdingReservationJdbcSupport.releaseReservedSellQuantity(accountId, symbol, quantity, updatedAt);
    }

    public boolean cancelOrder(long orderId, LocalDateTime updatedAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where id = ?
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                updatedAt,
                orderId
        );
        return updatedRows > 0;
    }

    public int rolloverClosingPrices(String symbol) {
        return jdbcTemplate.update(
                """
                update stock_price
                   set previous_close = current_price
                 where previous_close <> current_price
                   and (? is null or symbol = ?)
                """,
                symbol,
                symbol
        );
    }

    private void snapshotDailyExecutionPrices(
            long closeRunId,
            String symbol,
            LocalDateTime executionRangeStart,
            LocalDateTime executionRangeEnd
    ) {
        jdbcTemplate.update(
                """
                update stock_order_book_daily_snapshot snapshot
                   set open_price = coalesce((
                           select execution.price
                             from stock_execution execution
                            where execution.symbol = snapshot.symbol
                              and execution.source = 'INTERNAL_ORDER_BOOK'
                              and execution.side = 'BUY'
                              and execution.executed_at >= ?
                              and execution.executed_at < ?
                            order by execution.executed_at asc, execution.id asc
                            limit 1
                       ), 0),
                       high_price = coalesce((
                           select max(execution.price)
                             from stock_execution execution
                            where execution.symbol = snapshot.symbol
                              and execution.source = 'INTERNAL_ORDER_BOOK'
                              and execution.side = 'BUY'
                              and execution.executed_at >= ?
                              and execution.executed_at < ?
                       ), 0),
                       low_price = coalesce((
                           select min(execution.price)
                             from stock_execution execution
                            where execution.symbol = snapshot.symbol
                              and execution.source = 'INTERNAL_ORDER_BOOK'
                              and execution.side = 'BUY'
                              and execution.executed_at >= ?
                              and execution.executed_at < ?
                       ), 0),
                       last_execution_price = coalesce((
                           select execution.price
                             from stock_execution execution
                            where execution.symbol = snapshot.symbol
                              and execution.source = 'INTERNAL_ORDER_BOOK'
                              and execution.side = 'BUY'
                              and execution.executed_at >= ?
                              and execution.executed_at < ?
                            order by execution.executed_at desc, execution.id desc
                            limit 1
                       ), 0)
                 where snapshot.close_run_id = ?
                   and (? is null or snapshot.symbol = ?)
                """,
                executionRangeStart,
                executionRangeEnd,
                executionRangeStart,
                executionRangeEnd,
                executionRangeStart,
                executionRangeEnd,
                executionRangeStart,
                executionRangeEnd,
                closeRunId,
                symbol,
                symbol
        );
    }

    public int snapshotDailyAccountExecutions(
            long closeRunId,
            String symbol,
            LocalDate simulationTradeDate,
            LocalDateTime snapshotAt,
            LocalDateTime executionRangeStart,
            LocalDateTime executionRangeEnd
    ) {
        return jdbcTemplate.update(
                """
                insert into stock_execution_daily_account_snapshot(
                    close_run_id, symbol, simulation_trade_date, account_id, participant_category,
                    execution_count, buy_quantity, sell_quantity, buy_amount, sell_amount,
                    net_cash_flow, execution_amount, created_at
                )
                select ?,
                       execution.symbol,
                       ?,
                       execution.account_id,
                       case
                         when listing_config.user_key is not null then 'LISTING_UNDERWRITER'
                         when participant.user_key is not null then 'AUTO_PARTICIPANT'
                         else 'MANUAL_PARTICIPANT'
                       end,
                       count(*),
                       sum(case when execution.side = 'BUY' then execution.quantity else 0 end),
                       sum(case when execution.side = 'SELL' then execution.quantity else 0 end),
                       sum(case when execution.side = 'BUY' then execution.gross_amount else 0 end),
                       sum(case when execution.side = 'SELL' then execution.gross_amount else 0 end),
                       sum(case when execution.side = 'BUY' then -execution.net_amount else execution.net_amount end),
                       sum(execution.gross_amount),
                       ?
                  from stock_execution execution
                  join stock_account account on account.id = execution.account_id
                  left join stock_listing_auto_account_config listing_config
                    on listing_config.user_key = account.user_key
                   and listing_config.symbol = execution.symbol
                  left join stock_auto_participant participant
                    on participant.user_key = account.user_key
                 where execution.source = 'INTERNAL_ORDER_BOOK'
                   and execution.executed_at >= ?
                   and execution.executed_at < ?
                   and (? is null or execution.symbol = ?)
                 group by execution.symbol,
                          execution.account_id,
                          listing_config.user_key,
                          participant.user_key
                """,
                closeRunId,
                simulationTradeDate,
                snapshotAt,
                executionRangeStart,
                executionRangeEnd,
                symbol,
                symbol
        );
    }

    public int snapshotHoldings(long closeRunId, String symbol, LocalDateTime snapshotAt) {
        return jdbcTemplate.update(
                """
                insert into stock_holding_snapshot(
                    close_run_id, account_id, symbol, quantity, reserved_quantity, average_price, snapshot_at
                )
                select ?, account_id, symbol, quantity, reserved_quantity, average_price, ?
                  from stock_holding
                 where quantity > 0
                   and (? is null or symbol = ?)
                """,
                closeRunId,
                snapshotAt,
                symbol,
                symbol
        );
    }

    public int snapshotOrderBookDailySymbols(
            long closeRunId,
            String symbol,
            LocalDate simulationTradeDate,
            LocalDateTime snapshotAt,
            LocalDateTime executionRangeStart,
            LocalDateTime executionRangeEnd
    ) {
        int insertedCount = jdbcTemplate.update(
                """
                insert into stock_order_book_daily_snapshot(
                    close_run_id, symbol, simulation_trade_date, snapshot_at, created_at,
                    name, market, enabled, market_enabled, market_status,
                    issued_shares, tradable_shares, initial_price, tick_size, price_limit_rate,
                    close_price, previous_close, change_rate,
                    price_time, price_provider,
                    execution_count, execution_quantity, turnover_amount,
                    buy_quantity, sell_quantity, buy_net_amount, sell_net_amount,
                    open_order_count, open_buy_order_count, open_sell_order_count, reserved_buy_cash,
                    holder_count, holding_quantity, pending_corporate_action_count,
                    first_executed_at, last_executed_at
                )
                select ?,
                       i.symbol,
                       ?,
                       ?,
                       ?,
                       i.name,
                       i.market,
                       i.enabled,
                       coalesce(m.enabled, false),
                       coalesce(m.market_status, 'CLOSED'),
                       i.issued_shares,
                       i.tradable_shares,
                       i.initial_price,
                       i.tick_size,
                       i.price_limit_rate,
                       coalesce(p.current_price, i.initial_price),
                       coalesce(p.previous_close, i.initial_price),
                       case
                         when coalesce(p.previous_close, i.initial_price) > 0
                         then round(
                             (coalesce(p.current_price, i.initial_price) - coalesce(p.previous_close, i.initial_price))
                             * 100 / coalesce(p.previous_close, i.initial_price),
                             4
                         )
                         else 0
                       end,
                       p.price_time,
                       p.provider,
                       coalesce(e.execution_count, 0),
                       coalesce(e.execution_quantity, 0),
                       coalesce(e.turnover_amount, 0),
                       coalesce(e.buy_quantity, 0),
                       coalesce(e.sell_quantity, 0),
                       coalesce(e.buy_net_amount, 0),
                       coalesce(e.sell_net_amount, 0),
                       coalesce(o.open_order_count, 0),
                       coalesce(o.open_buy_order_count, 0),
                       coalesce(o.open_sell_order_count, 0),
                       coalesce(o.reserved_buy_cash, 0),
                       coalesce(h.holder_count, 0),
                       coalesce(h.holding_quantity, 0),
                       coalesce(c.pending_corporate_action_count, 0),
                       e.first_executed_at,
                       e.last_executed_at
                  from stock_order_book_instrument i
                  left join stock_order_book_market_config m on m.symbol = i.symbol
                  left join stock_price p on p.symbol = i.symbol
                  left join (
                       select symbol,
                              count(*) as execution_count,
                              sum(quantity) as execution_quantity,
                              sum(gross_amount) as turnover_amount,
                              sum(case when side = 'BUY' then quantity else 0 end) as buy_quantity,
                              sum(case when side = 'SELL' then quantity else 0 end) as sell_quantity,
                              sum(case when side = 'BUY' then net_amount else 0 end) as buy_net_amount,
                              sum(case when side = 'SELL' then net_amount else 0 end) as sell_net_amount,
                              min(executed_at) as first_executed_at,
                              max(executed_at) as last_executed_at
                         from stock_execution
                        where source = 'INTERNAL_ORDER_BOOK'
                          and executed_at >= ?
                          and executed_at < ?
                          and (? is null or symbol = ?)
                        group by symbol
                  ) e on e.symbol = i.symbol
                  left join (
                       select symbol,
                              count(*) as open_order_count,
                              sum(case when side = 'BUY' then 1 else 0 end) as open_buy_order_count,
                              sum(case when side = 'SELL' then 1 else 0 end) as open_sell_order_count,
                              sum(case when side = 'BUY' then reserved_cash else 0 end) as reserved_buy_cash
                         from stock_order
                        where market_type = 'ORDER_BOOK'
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                          and (? is null or symbol = ?)
                        group by symbol
                  ) o on o.symbol = i.symbol
                  left join (
                       select h.symbol,
                              count(distinct h.account_id) as holder_count,
                              sum(h.quantity) as holding_quantity
                         from stock_holding h
                         join stock_account a on a.id = h.account_id and a.status = 'ACTIVE'
                        where (? is null or h.symbol = ?)
                        group by h.symbol
                  ) h on h.symbol = i.symbol
                  left join (
                       select symbol,
                              count(*) as pending_corporate_action_count
                         from stock_corporate_action
                        where status in ('ANNOUNCED', 'EX_RIGHTS_APPLIED')
                          and (? is null or symbol = ?)
                        group by symbol
                  ) c on c.symbol = i.symbol
                 where (? is null or i.symbol = ?)
                """,
                closeRunId,
                simulationTradeDate,
                snapshotAt,
                snapshotAt,
                executionRangeStart,
                executionRangeEnd,
                symbol,
                symbol,
                symbol,
                symbol,
                symbol,
                symbol,
                symbol,
                symbol,
                symbol,
                symbol
        );
        snapshotDailyExecutionPrices(closeRunId, symbol, executionRangeStart, executionRangeEnd);
        return insertedCount;
    }

    public void completeCloseRun(
            long closeRunId,
            int cancelledOrderCount,
            int holdingSnapshotCount,
            int priceRolloverCount,
            LocalDateTime completedAt
    ) {
        jdbcTemplate.update(
                """
                update stock_market_close_run
                   set status = 'COMPLETED',
                       cancelled_order_count = ?,
                       holding_snapshot_count = ?,
                       price_rollover_count = ?,
                       completed_at = ?
                 where id = ?
                """,
                cancelledOrderCount,
                holdingSnapshotCount,
                priceRolloverCount,
                completedAt,
                closeRunId
        );
    }
}
