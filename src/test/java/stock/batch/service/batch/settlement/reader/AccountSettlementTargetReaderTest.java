package stock.batch.service.batch.settlement.reader;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AccountSettlementTargetReaderTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private AccountSettlementTargetReader readerFactory;

    @BeforeEach
    void setUp() {
        dataSource = BatchTestDatabaseFactory.createDataSource("portfolio_paging_reader");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        readerFactory = new AccountSettlementTargetReader(dataSource);
    }

    @Test
    void create_readsTargetsInStableAccountIdOrder() throws Exception {
        insertAccountSnapshot(
                2L, "user-b", "200000.00", "0.00", "0.00", "0.00", "200000.00",
                "0.00", 0L, 0L, 0L
        );
        insertAccountSnapshot(
                1L, "user-a", "100000.00", "90000.00", "30000.00", "20000.00", "130000.00",
                "150000.00", 5L, 3L, 2L
        );

        var reader = readerFactory.create(1, 10L);
        reader.open(new ExecutionContext());
        AccountSettlementTarget first = reader.read();
        AccountSettlementTarget second = reader.read();
        AccountSettlementTarget end = reader.read();
        reader.close();

        assertThat(first.accountId()).isEqualTo(1L);
        assertThat(first.cashBalance()).isEqualByComparingTo(new BigDecimal("130000.00"));
        assertThat(first.netCashFlow()).isEqualByComparingTo(new BigDecimal("90000.00"));
        assertThat(first.marketValue()).isEqualByComparingTo(new BigDecimal("150000.00"));
        assertThat(first.pendingSubscriptionAsset()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(first.holdingQuantity()).isEqualTo(5L);
        assertThat(first.reservedSellQuantity()).isEqualTo(3L);
        assertThat(first.holdingPositionCount()).isEqualTo(2L);
        assertThat(second.accountId()).isEqualTo(2L);
        assertThat(second.holdingQuantity()).isZero();
        assertThat(second.reservedSellQuantity()).isZero();
        assertThat(second.holdingPositionCount()).isZero();
        assertThat(end).isNull();
    }

    @Test
    void create_whenFrozenInputReconciliationFailed_returnsNoRows() throws Exception {
        insertAccountSnapshot(
                1L, "user-a", "100000.00", "100000.00", "0.00", "0.00", "100000.00",
                "0.00", 0L, 0L, 0L
        );
        jdbcTemplate.update(
                "update stock_close_account_snapshot set reconciliation_status = 'MISMATCHED' where account_id = 1"
        );
        var reader = readerFactory.create(10, 10L);
        reader.open(new ExecutionContext());

        AccountSettlementTarget target = reader.read();
        reader.close();

        assertThat(target).isNull();
    }

    @Test
    void create_selectsPhysicalPagingSortColumn() throws Exception {
        var reader = readerFactory.create(1, 10L);
        var queryProviderField = reader.getClass().getDeclaredField("queryProvider");
        queryProviderField.setAccessible(true);
        var queryProvider = (org.springframework.batch.infrastructure.item.database.PagingQueryProvider)
                queryProviderField.get(reader);

        assertThat(queryProvider.generateFirstPageQuery(1))
                .containsIgnoringCase("select account_snapshot.account_id,");
    }

    private void insertAccountSnapshot(
            long accountId,
            String userKey,
            String cashBalance,
            String netCashFlow,
            String orderReservedCash,
            String subscriptionReservedCash,
            String postCancelCash,
            String holdingMarketValue,
            long holdingQuantity,
            long reservedSellQuantity,
            long holdingPositionCount
    ) {
        jdbcTemplate.update(
                """
                insert into stock_close_account_snapshot(
                    close_cycle_id, close_run_id, account_id, user_key, settlement_target,
                    pre_cancel_cash, pre_cancel_order_reserved_cash, subscription_reserved_cash,
                    post_cancel_cash, external_net_cash_flow, holding_market_value,
                    holding_quantity, reserved_sell_quantity, holding_position_count,
                    reconciliation_status
                ) values (10, 20, ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MATCHED')
                """,
                accountId,
                userKey,
                new BigDecimal(cashBalance),
                new BigDecimal(orderReservedCash),
                new BigDecimal(subscriptionReservedCash),
                new BigDecimal(postCancelCash),
                new BigDecimal(netCashFlow),
                new BigDecimal(holdingMarketValue),
                holdingQuantity,
                reservedSellQuantity,
                holdingPositionCount
        );
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table stock_close_account_snapshot(
                    close_cycle_id bigint, close_run_id bigint, account_id bigint, user_key varchar(100),
                    settlement_target boolean, pre_cancel_cash decimal(19,2),
                    pre_cancel_order_reserved_cash decimal(19,2), subscription_reserved_cash decimal(19,2),
                    post_cancel_cash decimal(19,2), external_net_cash_flow decimal(19,2),
                    holding_market_value decimal(19,2), holding_quantity bigint,
                    reserved_sell_quantity bigint, holding_position_count bigint,
                    reconciliation_status varchar(20)
                )
                """);
    }
}
