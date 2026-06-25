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
        List<AccountRow> accounts = jdbcTemplate.query(
                """
                select a.id,
                       a.user_key,
                       a.cash_balance,
                       coalesce(sum(
	                           case
	                               when f.flow_type = 'DEPOSIT' and f.reason <> 'DIVIDEND_PAYMENT' then f.amount
	                               when f.flow_type = 'WITHDRAW' then -f.amount
	                               else 0
	                           end
                       ), 0) as net_cash_flow
                from stock_account a
                left join stock_account_cash_flow f on f.account_id = a.id
                where a.status = 'ACTIVE'
                  and a.user_key is not null
                  and a.user_key not like 'stock-listing-%'
                group by a.id, a.user_key, a.cash_balance
                """,
                (rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("user_key"),
                        rs.getBigDecimal("cash_balance"),
                        rs.getBigDecimal("net_cash_flow")
                )
        );
        return accounts.stream()
                .map(account -> new AccountSettlementTarget(
                        account.id(),
                        account.userKey(),
                        account.cashBalance(),
                        account.netCashFlow(),
                        calculateMarketValue(account.id()),
                        calculateReservedBuyCash(account.id())
                ))
                .toList();
    }

    private BigDecimal calculateMarketValue(long accountId) {
        BigDecimal value = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(h.quantity * coalesce(p.current_price, h.average_price)), 0)
                from stock_holding h
                left join stock_price p on p.symbol = h.symbol
                where h.account_id = ?
                """,
                BigDecimal.class,
                accountId
        );
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal calculateReservedBuyCash(long accountId) {
        BigDecimal value = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(reserved_cash), 0)
                from stock_order
                where account_id = ?
                  and side = 'BUY'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                BigDecimal.class,
                accountId
        );
        return value == null ? BigDecimal.ZERO : value;
    }

    private record AccountRow(long id, String userKey, BigDecimal cashBalance, BigDecimal netCashFlow) {
    }
}
