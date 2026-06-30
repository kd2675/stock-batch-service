package stock.batch.service.batch.settlement.reader;

import java.math.BigDecimal;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;

@Component
@RequiredArgsConstructor
public class AccountSettlementTargetReader {

    private final JdbcTemplate jdbcTemplate;

    public List<AccountSettlementTarget> readTargets() {
        return jdbcTemplate.query(
                """
                select a.id,
                       a.user_key,
                       a.cash_balance,
                       coalesce(cf.net_cash_flow, 0) as net_cash_flow,
                       coalesce(hv.market_value, 0) as market_value,
                       coalesce(bo.reserved_buy_cash, 0) as reserved_buy_cash
                from stock_account a
                left join (
                    select f.account_id,
                           sum(
                               case
                                   when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount
                                   when f.flow_type = 'WITHDRAW' then -f.amount
                                   else 0
                               end
                           ) as net_cash_flow
                    from stock_account_cash_flow f
                    group by f.account_id
                ) cf on cf.account_id = a.id
                left join (
                    select h.account_id,
                           sum(h.quantity * coalesce(p.current_price, h.average_price)) as market_value
                    from stock_holding h
                    left join stock_price p on p.symbol = h.symbol
                    group by h.account_id
                ) hv on hv.account_id = a.id
                left join (
                    select o.account_id,
                           sum(o.reserved_cash) as reserved_buy_cash
                    from stock_order o
                    where o.side = 'BUY'
                      and o.status in ('PENDING', 'PARTIALLY_FILLED')
                    group by o.account_id
                ) bo on bo.account_id = a.id
                where a.status = 'ACTIVE'
                  and a.user_key is not null
                  and a.user_key not like 'stock-listing-%'
                order by a.id asc
                """,
                (rs, rowNum) -> new AccountSettlementTarget(
                        rs.getLong("id"),
                        rs.getString("user_key"),
                        rs.getBigDecimal("cash_balance"),
                        nullToZero(rs.getBigDecimal("net_cash_flow")),
                        nullToZero(rs.getBigDecimal("market_value")),
                        nullToZero(rs.getBigDecimal("reserved_buy_cash"))
                )
        );
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
