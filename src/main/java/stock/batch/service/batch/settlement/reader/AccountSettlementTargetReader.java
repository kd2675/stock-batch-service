package stock.batch.service.batch.settlement.reader;

import java.math.BigDecimal;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.batch.settlement.model.AccountSettlementTarget;

@Component
public class AccountSettlementTargetReader {

    private static final String READER_NAME = "accountSettlementTargetReader";
    private static final String SELECT_CLAUSE = """
            select a.id as id,
                   a.user_key,
                   a.cash_balance,
                   coalesce((
                       select sum(
                           case
                               when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount
                               when f.flow_type = 'WITHDRAW'
                                   and f.reason <> 'CAPITAL_INCREASE_SUBSCRIPTION' then -f.amount
                               else 0
                           end
                       )
                         from stock_account_cash_flow f
                        where f.account_id = a.id
                   ), 0) as net_cash_flow,
                   coalesce(h.market_value, 0) as market_value,
                   coalesce(h.holding_quantity, 0) as holding_quantity,
                   coalesce(h.reserved_sell_quantity, 0) as reserved_sell_quantity,
                   coalesce(h.holding_position_count, 0) as holding_position_count,
                   coalesce((
                       select sum(o.reserved_cash)
                         from stock_order o
                        where o.account_id = a.id
                          and o.side = 'BUY'
                          and o.status in ('PENDING', 'PARTIALLY_FILLED')
                   ), 0) + coalesce((
                       select sum(e.subscribed_cash_amount)
                         from stock_corporate_action_entitlement e
                        where e.account_id = a.id
                          and e.status = 'SUBSCRIBED'
                          and e.subscribed_cash_amount is not null
                   ), 0) as reserved_buy_cash
            """;
    private static final String FROM_CLAUSE = """
            from stock_account a
            left join (
              select h.account_id,
                     sum(h.quantity * coalesce(p.current_price, h.average_price)) as market_value,
                     sum(h.quantity) as holding_quantity,
                     sum(h.reserved_quantity) as reserved_sell_quantity,
                     sum(case when h.quantity > 0 then 1 else 0 end) as holding_position_count
                from stock_holding h
                left join stock_price p on p.symbol = h.symbol
               group by h.account_id
            ) h on h.account_id = a.id
            """;
    private static final String ELIGIBLE_ACCOUNT_CLAUSE = """
            a.status = 'ACTIVE'
            and a.user_key is not null
            and a.user_key not like 'stock-listing-%'
            """;

    private final DataSource dataSource;

    public AccountSettlementTargetReader(
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE) DataSource dataSource
    ) {
        this.dataSource = dataSource;
    }

    public JdbcPagingItemReader<AccountSettlementTarget> create(int pageSize, boolean eligible) throws Exception {
        return new JdbcPagingItemReaderBuilder<AccountSettlementTarget>()
                .name(READER_NAME)
                .dataSource(dataSource)
                .pageSize(pageSize)
                .fetchSize(pageSize)
                .saveState(true)
                .selectClause(SELECT_CLAUSE)
                .fromClause(FROM_CLAUSE)
                .whereClause(ELIGIBLE_ACCOUNT_CLAUSE + (eligible ? " and 1 = 1" : " and 1 = 0"))
                .sortKeys(Map.of("id", Order.ASCENDING))
                .rowMapper((rs, rowNum) -> new AccountSettlementTarget(
                        rs.getLong("id"),
                        rs.getString("user_key"),
                        rs.getBigDecimal("cash_balance"),
                        nullToZero(rs.getBigDecimal("net_cash_flow")),
                        nullToZero(rs.getBigDecimal("market_value")),
                        nullToZero(rs.getBigDecimal("reserved_buy_cash")),
                        rs.getLong("holding_quantity"),
                        rs.getLong("reserved_sell_quantity"),
                        rs.getLong("holding_position_count")
                ))
                .build();
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
