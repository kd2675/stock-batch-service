package stock.batch.service.settlement.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PortfolioSettlementService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public int settleToday() {
        List<AccountRow> accounts = jdbcTemplate.query(
                "select user_key, cash_balance, initial_cash from stock_account",
                (rs, rowNum) -> new AccountRow(
                        rs.getString("user_key"),
                        rs.getBigDecimal("cash_balance"),
                        rs.getBigDecimal("initial_cash")
                )
        );
        for (AccountRow account : accounts) {
            BigDecimal marketValue = calculateMarketValue(account.userKey());
            BigDecimal reservedBuyCash = calculateReservedBuyCash(account.userKey());
            BigDecimal totalAsset = account.cashBalance().add(reservedBuyCash).add(marketValue);
            BigDecimal returnRate = BigDecimal.ZERO;
            if (account.initialCash().compareTo(BigDecimal.ZERO) > 0) {
                returnRate = totalAsset.subtract(account.initialCash())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(account.initialCash(), 4, RoundingMode.HALF_UP);
            }
            upsertSnapshot(account, marketValue, totalAsset, returnRate);
        }
        return accounts.size();
    }

    private BigDecimal calculateMarketValue(String userKey) {
        BigDecimal value = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(h.quantity * coalesce(p.current_price, h.average_price)), 0)
                from stock_holding h
                left join stock_price p on p.symbol = h.symbol
                where h.user_key = ?
                """,
                BigDecimal.class,
                userKey
        );
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal calculateReservedBuyCash(String userKey) {
        BigDecimal value = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(reserved_cash), 0)
                from stock_order
                where user_key = ?
                  and side = 'BUY'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                BigDecimal.class,
                userKey
        );
        return value == null ? BigDecimal.ZERO : value;
    }

    private void upsertSnapshot(AccountRow account, BigDecimal marketValue, BigDecimal totalAsset, BigDecimal returnRate) {
        LocalDate today = LocalDate.now();
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from portfolio_snapshot where user_key = ? and snapshot_date = ?",
                Integer.class,
                account.userKey(),
                today
        );
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    """
                    update portfolio_snapshot
                    set total_asset = ?, cash_balance = ?, market_value = ?, return_rate = ?, created_at = ?
                    where user_key = ? and snapshot_date = ?
                    """,
                    totalAsset,
                    account.cashBalance(),
                    marketValue,
                    returnRate,
                    LocalDateTime.now(),
                    account.userKey(),
                    today
            );
            return;
        }

        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(user_key, snapshot_date, total_asset, cash_balance, market_value, return_rate, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                account.userKey(),
                today,
                totalAsset,
                account.cashBalance(),
                marketValue,
                returnRate,
                LocalDateTime.now()
        );
    }

    private record AccountRow(String userKey, BigDecimal cashBalance, BigDecimal initialCash) {
    }
}
