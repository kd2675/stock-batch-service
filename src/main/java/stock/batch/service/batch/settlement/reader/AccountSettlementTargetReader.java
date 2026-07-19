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
            select account_snapshot.account_id,
                   account_snapshot.close_cycle_id,
                   account_snapshot.close_run_id,
                   account_snapshot.user_key,
                   account_snapshot.post_cancel_cash as cash_balance,
                   account_snapshot.external_net_cash_flow as net_cash_flow,
                   account_snapshot.holding_market_value as market_value,
                   account_snapshot.holding_quantity,
                   account_snapshot.reserved_sell_quantity,
                   account_snapshot.holding_position_count,
                   account_snapshot.subscription_reserved_cash as pending_subscription_asset
            """;
    private static final String FROM_CLAUSE = """
            from stock_close_account_snapshot account_snapshot
            """;
    private static final String ELIGIBLE_ACCOUNT_CLAUSE = """
            account_snapshot.close_cycle_id = :cycleId
            and account_snapshot.settlement_target = true
            and account_snapshot.reconciliation_status = 'MATCHED'
            """;

    private final DataSource dataSource;

    public AccountSettlementTargetReader(
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE) DataSource dataSource
    ) {
        this.dataSource = dataSource;
    }

    public JdbcPagingItemReader<AccountSettlementTarget> create(
            int pageSize,
            long closeCycleId
    ) throws Exception {
        return new JdbcPagingItemReaderBuilder<AccountSettlementTarget>()
                .name(READER_NAME)
                .dataSource(dataSource)
                .pageSize(pageSize)
                .fetchSize(pageSize)
                .saveState(true)
                .selectClause(SELECT_CLAUSE)
                .fromClause(FROM_CLAUSE)
                .whereClause(ELIGIBLE_ACCOUNT_CLAUSE)
                .parameterValues(Map.of("cycleId", closeCycleId))
                .sortKeys(Map.of("account_id", Order.ASCENDING))
                .rowMapper((rs, rowNum) -> new AccountSettlementTarget(
                        rs.getLong("close_cycle_id"),
                        rs.getLong("close_run_id"),
                        rs.getLong("account_id"),
                        rs.getString("user_key"),
                        rs.getBigDecimal("cash_balance"),
                        nullToZero(rs.getBigDecimal("net_cash_flow")),
                        nullToZero(rs.getBigDecimal("market_value")),
                        nullToZero(rs.getBigDecimal("pending_subscription_asset")),
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
