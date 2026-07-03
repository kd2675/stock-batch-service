package stock.batch.service.batch.automarket.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantSymbolStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AutoMarketReaderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoMarketReader reader;

    @BeforeEach
    void setUp() {
        reader = new AutoMarketReader(jdbcTemplate);
    }

    @Test
    void findEnabledParticipantStrategiesBySymbol_readsActiveParticipantsAndSymbolOverridesOnce() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate("auto_market_reader_strategy_test");
        realJdbcTemplate.execute("""
                create table stock_auto_participant (
                    user_key varchar(64) not null,
                    display_name varchar(64) not null,
                    profile_type varchar(64) not null,
                    enabled boolean not null,
                    withdrawn_at timestamp null,
                    recurring_cash_amount decimal(19, 2) null,
                    recurring_cash_interval_value decimal(19, 2) null,
                    recurring_cash_interval_unit varchar(32) null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_account (
                    id bigint not null,
                    user_key varchar(64) not null,
                    status varchar(32) not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_auto_participant_symbol_config (
                    user_key varchar(64) not null,
                    symbol varchar(20) not null,
                    enabled boolean not null,
                    intensity int not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_auto_market_config (
                    symbol varchar(20) not null,
                    enabled boolean not null
                )
                """);
        realJdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, profile_type, enabled, withdrawn_at,
                    recurring_cash_amount, recurring_cash_interval_value, recurring_cash_interval_unit
                ) values (?, ?, ?, true, null, ?, ?, ?)
                """,
                "auto-001",
                "auto 1",
                "NEWS_REACTIVE",
                new BigDecimal("1000.00"),
                new BigDecimal("1.00"),
                "DAY"
        );
        realJdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, profile_type, enabled, withdrawn_at,
                    recurring_cash_amount, recurring_cash_interval_value, recurring_cash_interval_unit
                ) values (?, ?, ?, true, null, ?, ?, ?)
                """,
                "auto-002",
                "auto 2",
                "NOISE",
                null,
                null,
                null
        );
        realJdbcTemplate.update("insert into stock_account(id, user_key, status) values (?, ?, 'ACTIVE')", 10L, "auto-001");
        realJdbcTemplate.update("insert into stock_account(id, user_key, status) values (?, ?, 'ACTIVE')", 20L, "auto-002");
        realJdbcTemplate.update(
                "insert into stock_auto_participant_symbol_config(user_key, symbol, enabled, intensity) values (?, ?, true, ?)",
                "auto-001",
                "STOCK001",
                9
        );
        realJdbcTemplate.update(
                "insert into stock_auto_participant_symbol_config(user_key, symbol, enabled, intensity) values (?, ?, false, ?)",
                "auto-002",
                "STOCK001",
                1
        );
        AutoMarketReader realReader = new AutoMarketReader(realJdbcTemplate);
        AutoMarketConfig stock001 = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoMarketConfig stock002 = new AutoMarketConfig(
                "STOCK002",
                8,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30000.00"),
                new BigDecimal("30000.00"),
                null
        );

        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = realReader.findEnabledParticipantStrategiesBySymbol(List.of(stock001, stock002));

        assertThat(strategiesBySymbol.get("STOCK001"))
                .extracting(AutoParticipantStrategy::userKey)
                .containsExactly("auto-001");
        assertThat(strategiesBySymbol.get("STOCK001").getFirst().intensity()).isEqualTo(9);
        assertThat(strategiesBySymbol.get("STOCK002"))
                .extracting(AutoParticipantStrategy::userKey)
                .containsExactly("auto-001", "auto-002");
        assertThat(strategiesBySymbol.get("STOCK002"))
                .extracting(AutoParticipantStrategy::intensity)
                .containsExactly(8, 8);
    }

    @Test
    void findDueParticipantSymbolStrategies_readsDueParticipantsWithAllEligibleSymbols() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate("auto_market_reader_due_participant_test");
        realJdbcTemplate.execute("""
                create table stock_auto_participant (
                    user_key varchar(64) not null,
                    display_name varchar(64) not null,
                    profile_type varchar(64) not null,
                    enabled boolean not null,
                    withdrawn_at timestamp null,
                    recurring_cash_amount decimal(19, 2) null,
                    recurring_cash_interval_value decimal(19, 2) null,
                    recurring_cash_interval_unit varchar(32) null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_account (
                    id bigint not null,
                    user_key varchar(64) not null,
                    status varchar(32) not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_auto_participant_symbol_config (
                    user_key varchar(64) not null,
                    symbol varchar(20) not null,
                    enabled boolean not null,
                    intensity int not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_auto_market_config (
                    symbol varchar(20) not null,
                    enabled boolean not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_auto_participant_order_schedule (
                    user_key varchar(64) not null,
                    profile_type varchar(40) not null,
                    next_run_at timestamp not null,
                    last_run_at timestamp null,
                    lease_until timestamp null,
                    lease_owner varchar(80) null,
                    run_interval_seconds int not null,
                    priority int not null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        realJdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, display_name, profile_type, enabled, withdrawn_at,
                    recurring_cash_amount, recurring_cash_interval_value, recurring_cash_interval_unit
                ) values
                ('auto-001', 'auto 1', 'MOMENTUM_FOLLOWER', true, null, null, null, null),
                ('auto-002', 'auto 2', 'NOISE_TRADER', true, null, null, null, null)
                """);
        realJdbcTemplate.update("insert into stock_account(id, user_key, status) values (10, 'auto-001', 'ACTIVE'), (20, 'auto-002', 'ACTIVE')");
        realJdbcTemplate.update("insert into stock_auto_participant_symbol_config(user_key, symbol, enabled, intensity) values ('auto-001', 'STOCK002', true, 9)");
        realJdbcTemplate.update("insert into stock_auto_market_config(symbol, enabled) values ('STOCK001', true), ('STOCK002', true)");
        realJdbcTemplate.update("""
                insert into stock_auto_participant_order_schedule(
                    user_key, profile_type, next_run_at, last_run_at,
                    lease_until, lease_owner, run_interval_seconds, priority, created_at, updated_at
                ) values
                ('auto-001', 'MOMENTUM_FOLLOWER', TIMESTAMP '2026-07-03 08:58:00', null, null, null, 10, 80, current_timestamp, current_timestamp),
                ('auto-002', 'NOISE_TRADER', TIMESTAMP '2026-07-03 08:57:00', null, TIMESTAMP '2026-07-03 09:10:00', 'other', 10, 90, current_timestamp, current_timestamp)
                """);
        AutoMarketReader realReader = new AutoMarketReader(realJdbcTemplate);
        AutoMarketConfig stock001 = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoMarketConfig stock002 = new AutoMarketConfig(
                "STOCK002",
                8,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30000.00"),
                new BigDecimal("30000.00"),
                null
        );

        List<AutoParticipantSymbolStrategy> strategies = realReader.findDueParticipantSymbolStrategies(
                List.of(stock001, stock002),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                10
        );

        assertThat(strategies).hasSize(2);
        assertThat(strategies)
                .extracting(AutoParticipantSymbolStrategy::symbol)
                .containsExactly("STOCK001", "STOCK002");
        assertThat(strategies)
                .extracting(strategy -> strategy.strategy().userKey())
                .containsExactly("auto-001", "auto-001");
        assertThat(strategies)
                .extracting(strategy -> strategy.strategy().intensity())
                .containsExactly(5, 9);
    }

    @Test
    void findTradingSnapshots_readsCashHoldingAndDividendWithSingleQuery() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate("auto_market_reader_snapshot_test");
        realJdbcTemplate.execute("""
                create table stock_account (
                    id bigint not null,
                    cash_balance decimal(19, 2) not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_account_cash_flow (
                    account_id bigint not null,
                    flow_type varchar(32) not null,
                    reason varchar(64) not null,
                    amount decimal(19, 2) not null,
                    created_at timestamp not null
                )
                """);
        LocalDateTime since = LocalDateTime.of(2026, 6, 29, 9, 0);
        realJdbcTemplate.update("insert into stock_account(id, cash_balance) values (?, ?)", 10L, new BigDecimal("100000.00"));
        realJdbcTemplate.update("insert into stock_account(id, cash_balance) values (?, ?)", 20L, new BigDecimal("50000.00"));
        realJdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price) values (?, ?, ?, ?, ?)",
                10L,
                "STOCK001",
                10L,
                3L,
                new BigDecimal("50000.00")
        );
        realJdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, flow_type, reason, amount, created_at) values (?, 'DEPOSIT', 'DIVIDEND_PAYMENT', ?, ?)",
                10L,
                new BigDecimal("3000.00"),
                since.plusMinutes(1)
        );
        realJdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, flow_type, reason, amount, created_at) values (?, 'DEPOSIT', 'DIVIDEND_PAYMENT', ?, ?)",
                10L,
                new BigDecimal("9999.00"),
                since.minusMinutes(1)
        );
        AutoMarketReader realReader = new AutoMarketReader(realJdbcTemplate);

        List<AutoParticipantTradingSnapshot> snapshots = realReader.findTradingSnapshots(List.of(10L, 20L), "STOCK001", since);

        assertThat(snapshots).hasSize(2);
        AutoParticipantTradingSnapshot snapshot = snapshots.getFirst();
        assertThat(snapshot.accountId()).isEqualTo(10L);
        assertThat(snapshot.cashBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(snapshot.availableQuantity()).isEqualTo(7L);
        assertThat(snapshot.averagePrice()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(snapshot.recentDividendCashAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        AutoParticipantTradingSnapshot emptyHoldingSnapshot = snapshots.get(1);
        assertThat(emptyHoldingSnapshot.accountId()).isEqualTo(20L);
        assertThat(emptyHoldingSnapshot.availableQuantity()).isZero();
        assertThat(emptyHoldingSnapshot.recentDividendCashAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void findTradingSnapshots_readsManyParticipantsWithoutRepeatedAccountIdBinding() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate("auto_market_reader_many_snapshot_test");
        realJdbcTemplate.execute("""
                create table stock_account (
                    id bigint not null,
                    cash_balance decimal(19, 2) not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_holding (
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    average_price decimal(19, 2) not null
                )
                """);
        realJdbcTemplate.execute("""
                create table stock_account_cash_flow (
                    account_id bigint not null,
                    flow_type varchar(32) not null,
                    reason varchar(64) not null,
                    amount decimal(19, 2) not null,
                    created_at timestamp not null
                )
                """);
        List<Long> accountIds = IntStream.rangeClosed(1, 42)
                .mapToObj(accountId -> (long) accountId)
                .toList();
        for (Long accountId : accountIds) {
            realJdbcTemplate.update(
                    "insert into stock_account(id, cash_balance) values (?, ?)",
                    accountId,
                    new BigDecimal("10000.00")
            );
        }
        LocalDateTime since = LocalDateTime.of(2026, 7, 1, 9, 0);
        realJdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, flow_type, reason, amount, created_at) values (?, 'DEPOSIT', 'DIVIDEND_PAYMENT', ?, ?)",
                42L,
                new BigDecimal("4200.00"),
                since.plusMinutes(1)
        );
        AutoMarketReader realReader = new AutoMarketReader(realJdbcTemplate);

        List<AutoParticipantTradingSnapshot> snapshots = realReader.findTradingSnapshots(accountIds, "STOCK001", since);

        assertThat(snapshots).hasSize(42);
        assertThat(snapshots.getLast().accountId()).isEqualTo(42L);
        assertThat(snapshots.getLast().recentDividendCashAmount()).isEqualByComparingTo(new BigDecimal("4200.00"));
    }

    @Test
    void findLatestPriceAtOrBefore_readsLatestPositivePriceWithJdbcClient() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate("auto_market_reader_price_test");
        realJdbcTemplate.execute("""
                create table stock_price_tick (
                    id bigint auto_increment primary key,
                    symbol varchar(20) not null,
                    price decimal(19, 2) not null,
                    price_time timestamp not null
                )
                """);
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK001",
                new BigDecimal("70000.00"),
                LocalDateTime.of(2026, 6, 30, 9, 0)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK001",
                new BigDecimal("70100.00"),
                LocalDateTime.of(2026, 6, 30, 9, 5)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "OTHER",
                new BigDecimal("99999.00"),
                LocalDateTime.of(2026, 6, 30, 9, 6)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK001",
                BigDecimal.ZERO,
                LocalDateTime.of(2026, 6, 30, 9, 10)
        );
        AutoMarketReader realReader = new AutoMarketReader(realJdbcTemplate);

        assertThat(realReader.findLatestPriceAtOrBefore("stock001", LocalDateTime.of(2026, 6, 30, 9, 6)))
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo(new BigDecimal("70100.00")));
        assertThat(realReader.findLatestPriceAtOrBefore("STOCK001", LocalDateTime.of(2026, 6, 30, 9, 10)))
                .isEmpty();
        assertThat(realReader.findLatestPriceAtOrBefore("UNKNOWN", LocalDateTime.of(2026, 6, 30, 9, 10)))
                .isEmpty();
    }

    @Test
    void findLatestPricesAtOrBefore_readsLatestPricesBySymbolWithSingleQuery() {
        JdbcTemplate realJdbcTemplate = createJdbcTemplate("auto_market_reader_prices_test");
        realJdbcTemplate.execute("""
                create table stock_price_tick (
                    id bigint auto_increment primary key,
                    symbol varchar(20) not null,
                    price decimal(19, 2) not null,
                    price_time timestamp not null
                )
                """);
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK001",
                new BigDecimal("70000.00"),
                LocalDateTime.of(2026, 6, 30, 9, 0)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK001",
                BigDecimal.ZERO,
                LocalDateTime.of(2026, 6, 30, 9, 5)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK002",
                new BigDecimal("30000.00"),
                LocalDateTime.of(2026, 6, 30, 9, 1)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK002",
                new BigDecimal("30100.00"),
                LocalDateTime.of(2026, 6, 30, 9, 3)
        );
        realJdbcTemplate.update(
                "insert into stock_price_tick(symbol, price, price_time) values (?, ?, ?)",
                "STOCK003",
                new BigDecimal("99999.00"),
                LocalDateTime.of(2026, 6, 30, 9, 9)
        );
        AutoMarketReader realReader = new AutoMarketReader(realJdbcTemplate);

        Map<String, BigDecimal> pricesBySymbol = realReader.findLatestPricesAtOrBefore(
                List.of("stock001", "STOCK002", "stock002", "UNKNOWN"),
                LocalDateTime.of(2026, 6, 30, 9, 6)
        );

        assertThat(pricesBySymbol).containsOnlyKeys("STOCK002");
        assertThat(pricesBySymbol.get("STOCK002")).isEqualByComparingTo(new BigDecimal("30100.00"));
    }

    private JdbcTemplate createJdbcTemplate(String databaseName) {
        return new JdbcTemplate(BatchTestDatabaseFactory.createDataSource(databaseName));
    }
}
