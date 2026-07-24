package stock.batch.service.mysql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;
import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.batch.execution.model.OrderBookOrderFillUpdate;
import stock.batch.service.batch.execution.writer.ExecutionHoldingJdbcSupport;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mysql")
@Testcontainers
class StockMysqlConcurrencyTest {

    private static final Duration FUTURE_TIMEOUT = Duration.ofSeconds(8);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("stock_eod_test")
            .withUsername("stock_test")
            .withPassword("stock_test");

    @BeforeEach
    void setUpSchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists stock_auto_participant_order_budget");
            statement.execute("drop table if exists stock_auto_participant_funding_budget");
            statement.execute("drop table if exists stock_order");
            statement.execute("drop table if exists stock_holding");
            statement.execute("drop table if exists stock_account");
            statement.execute("drop table if exists stock_market_session_fence");
            statement.execute("drop table if exists stock_order_book_market_config");
            statement.execute("drop table if exists stock_virtual_market_config");
            statement.execute("drop table if exists stock_market_business_state");
            statement.execute("drop table if exists stock_simulation_clock");
            statement.execute("drop table if exists stock_batch_job_signal");
            statement.execute(
                    """
                    create table stock_account (
                        id bigint not null primary key,
                        user_key varchar(100) not null,
                        status varchar(20) not null,
                        cash_balance decimal(19, 2) not null default 0,
                        revision bigint not null,
                        updated_at datetime(6) null,
                        unique key uk_stock_account_user_key (user_key)
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_holding (
                        account_id bigint not null,
                        symbol varchar(20) not null,
                        quantity bigint not null,
                        reserved_quantity bigint not null default 0,
                        average_price decimal(19, 2) not null default 0,
                        updated_at datetime(6) null,
                        primary key (account_id, symbol)
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_order (
                        id bigint not null primary key,
                        account_id bigint not null,
                        market_type varchar(20) not null,
                        symbol varchar(20) not null,
                        side varchar(10) not null default 'BUY',
                        order_type varchar(20) not null default 'LIMIT',
                        status varchar(30) not null,
                        limit_price decimal(19, 2) null,
                        quantity bigint not null default 1,
                        filled_quantity bigint not null default 0,
                        average_fill_price decimal(19, 2) null,
                        reserved_cash decimal(19, 2) not null default 0,
                        funding_budget_type varchar(20) null,
                        created_at datetime(6) not null,
                        updated_at datetime(6) null,
                        key idx_stock_order_market_status_symbol (
                            market_type, status, symbol
                        )
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_market_session_fence (
                        market_type varchar(20) not null,
                        symbol varchar(20) not null,
                        business_date date not null,
                        session_epoch bigint not null,
                        session_state varchar(20) not null,
                        primary key (market_type, symbol)
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_order_book_market_config (
                        symbol varchar(20) not null primary key,
                        enabled boolean not null,
                        market_status varchar(20) not null,
                        revision bigint not null
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_virtual_market_config (
                        symbol varchar(20) not null primary key,
                        enabled boolean not null,
                        market_status varchar(20) not null,
                        revision bigint not null
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_market_business_state (
                        state_id varchar(30) not null primary key,
                        active_business_date date not null,
                        preparing_business_date date null,
                        raw_simulation_date date not null,
                        revision bigint not null
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_simulation_clock (
                        clock_id varchar(30) not null primary key,
                        base_simulation_date date not null,
                        real_seconds_per_simulation_day int not null,
                        accumulated_real_seconds bigint not null,
                        running boolean not null,
                        last_started_at datetime(6) null,
                        last_heartbeat_at datetime(6) null,
                        revision bigint not null
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_batch_job_signal (
                        id bigint not null primary key,
                        status varchar(20) not null,
                        next_attempt_at datetime(6) not null,
                        eligible_at datetime(6) null,
                        lease_until datetime(6) null,
                        key idx_stock_batch_job_signal_claim (
                            status, next_attempt_at, eligible_at, id
                        ),
                        key idx_stock_batch_job_signal_lease (
                            status, lease_until, id
                        )
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_auto_participant_funding_budget (
                        id bigint not null primary key,
                        available_amount decimal(19, 2) not null,
                        reserved_amount decimal(19, 2) not null,
                        spent_amount decimal(19, 2) not null,
                        expires_business_date date null,
                        status varchar(20) not null,
                        updated_at datetime(6) not null default current_timestamp(6)
                    ) engine=InnoDB
                    """
            );
            statement.execute(
                    """
                    create table stock_auto_participant_order_budget (
                        order_id bigint not null,
                        budget_id bigint not null,
                        remaining_reserved_amount decimal(19, 2) not null,
                        spent_amount decimal(19, 2) not null,
                        released_amount decimal(19, 2) not null,
                        created_at datetime(6) not null default current_timestamp(6),
                        updated_at datetime(6) not null default current_timestamp(6),
                        primary key (order_id, budget_id),
                        key idx_stock_auto_order_budget_budget (budget_id, order_id)
                    ) engine=InnoDB
                    """
            );
        }
    }

    @Test
    void exactPrimaryKeyOrderLock_doesNotGapLockAdjacentOpenOrderInsert() throws Exception {
        execute(
                """
                insert into stock_order(id, account_id, market_type, symbol, status, created_at)
                values (1, 101, 'ORDER_BOOK', 'DEMO001', 'PENDING', now(6)),
                       (2, 102, 'ORDER_BOOK', 'DEMO001', 'PARTIALLY_FILLED', now(6))
                """
        );
        String exactLockPlan = querySingleString(
                """
                explain format=json
                select stock_order.id
                  from stock_order force index (primary)
                  join (
                      select cast(1 as decimal(19, 0)) as id
                      union all
                      select cast(2 as decimal(19, 0)) as id
                  ) selected_order
                    on selected_order.id = stock_order.id
                 order by stock_order.id
                 for update
                """
        );
        assertThat(exactLockPlan)
                .contains(
                        "\"table_name\": \"stock_order\"",
                        "\"access_type\": \"eq_ref\"",
                        "\"key\": \"PRIMARY\""
                );

        try (Connection locker = transactionConnection()) {
            querySingleLong(
                    locker,
                    """
                    select stock_order.id
                      from stock_order force index (primary)
                      join (
                          select 1 as id
                          union all
                          select 2 as id
                      ) selected_order
                        on selected_order.id = stock_order.id
                     order by stock_order.id
                     for update
                    """
            );

            var executor = Executors.newSingleThreadExecutor();
            try {
                var inserted = executor.submit(() -> {
                    try (Connection inserter = connection(); Statement statement = inserter.createStatement()) {
                        statement.execute("set session innodb_lock_wait_timeout = 2");
                        return statement.executeUpdate(
                                """
                                insert into stock_order(
                                    id, account_id, market_type, symbol, status, created_at
                                ) values (3, 103, 'ORDER_BOOK', 'DEMO001', 'PENDING', now(6))
                                """
                        );
                    }
                });

                assertThat(inserted.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1);
                var lockedRowUpdate = executor.submit(() -> {
                    try (Connection updater = connection(); Statement statement = updater.createStatement()) {
                        statement.execute("set session innodb_lock_wait_timeout = 4");
                        return statement.executeUpdate(
                                "update stock_order set updated_at = now(6) where id = 2"
                        );
                    }
                });
                Thread.sleep(200);
                assertThat(lockedRowUpdate).isNotDone();

                locker.rollback();
                assertThat(lockedRowUpdate.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                        .isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(querySingleLong("select count(*) from stock_order where id = 3")).isEqualTo(1L);
    }

    @Test
    void exactPrimaryKeyAccountLock_doesNotGapLockAdjacentAccountInsert() throws Exception {
        execute(
                """
                insert into stock_account(id, user_key, status, cash_balance, revision)
                values (1, 'account-1', 'ACTIVE', 0.00, 1),
                       (2, 'account-2', 'ACTIVE', 0.00, 1)
                """
        );
        String exactLockPlan = querySingleString(
                """
                explain format=json
                select stock_account.id
                  from stock_account force index (primary)
                  join (
                      select cast(1 as decimal(19, 0)) as id
                      union all
                      select cast(2 as decimal(19, 0)) as id
                  ) selected_account
                    on selected_account.id = stock_account.id
                 order by stock_account.id
                 for update
                """
        );
        assertThat(exactLockPlan)
                .contains(
                        "\"table_name\": \"stock_account\"",
                        "\"access_type\": \"eq_ref\"",
                        "\"key\": \"PRIMARY\""
                );

        try (Connection locker = transactionConnection()) {
            querySingleLong(
                    locker,
                    """
                    select stock_account.id
                      from stock_account force index (primary)
                      join (
                          select cast(1 as decimal(19, 0)) as id
                          union all
                          select cast(2 as decimal(19, 0)) as id
                      ) selected_account
                        on selected_account.id = stock_account.id
                     order by stock_account.id
                     for update
                    """
            );

            var executor = Executors.newSingleThreadExecutor();
            try {
                var inserted = executor.submit(() -> {
                    try (Connection inserter = connection(); Statement statement = inserter.createStatement()) {
                        statement.execute("set session innodb_lock_wait_timeout = 2");
                        return statement.executeUpdate(
                                """
                                insert into stock_account(
                                    id, user_key, status, cash_balance, revision
                                ) values (3, 'account-3', 'ACTIVE', 0.00, 1)
                                """
                        );
                    }
                });

                assertThat(inserted.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1);
                var lockedRowUpdate = executor.submit(() -> {
                    try (Connection updater = connection(); Statement statement = updater.createStatement()) {
                        statement.execute("set session innodb_lock_wait_timeout = 4");
                        return statement.executeUpdate(
                                "update stock_account set updated_at = now(6) where id = 2"
                        );
                    }
                });
                Thread.sleep(200);
                assertThat(lockedRowUpdate).isNotDone();

                locker.rollback();
                assertThat(lockedRowUpdate.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                        .isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(querySingleLong("select count(*) from stock_account where id = 3")).isEqualTo(1L);
    }

    @Test
    void exactPrimaryKeyBatchUpdates_useEqRefPrimaryKeyPlans() throws Exception {
        String accountUpdatePlan = querySingleString(
                """
                explain format=json
                update stock_account force index (primary)
                  join (
                      select cast(1 as decimal(19, 0)) as id
                      union all
                      select cast(2 as decimal(19, 0)) as id
                  ) selected_account
                    on selected_account.id = stock_account.id
                   set stock_account.cash_balance = stock_account.cash_balance
                """
        );
        String orderUpdatePlan = querySingleString(
                """
                explain format=json
                update stock_order force index (primary)
                  join (
                      select cast(1 as decimal(19, 0)) as id
                      union all
                      select cast(2 as decimal(19, 0)) as id
                  ) selected_order
                    on selected_order.id = stock_order.id
                   set stock_order.updated_at = stock_order.updated_at
                """
        );

        assertThat(new String[]{accountUpdatePlan, orderUpdatePlan})
                .allSatisfy(plan -> assertThat(plan)
                        .contains(
                                "\"access_type\": \"eq_ref\"",
                                "\"key\": \"PRIMARY\""
                        ));
    }

    @Test
    void openOrderCaptureKeyset_usesExistingStatusSymbolIndexWithoutFilesort() throws Exception {
        execute(
                """
                insert into stock_order(id, account_id, market_type, symbol, status, quantity, filled_quantity, created_at)
                values (101, 101, 'ORDER_BOOK', 'DEMO001', 'PENDING', 10, 0, now(6)),
                       (102, 102, 'ORDER_BOOK', 'DEMO001', 'PENDING', 10, 10, now(6)),
                       (103, 103, 'ORDER_BOOK', 'DEMO002', 'PENDING', 10, 0, now(6))
                """
        );

        String explainJson = querySingleString(
                """
                explain format=json
                select id
                  from stock_order force index (idx_stock_order_market_status_symbol)
                 where market_type = 'ORDER_BOOK'
                   and status = 'PENDING'
                   and symbol = 'DEMO001'
                   and id > 100
                   and quantity > filled_quantity
                 order by id
                 limit 1000
                """
        );

        assertThat(explainJson)
                .contains("idx_stock_order_market_status_symbol")
                .doesNotContain("\"using_filesort\": true");
    }

    @Test
    void sessionFenceClose_waitsForInflightSharedPermitThenRejectsNextOrder() throws Exception {
        execute(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state
                ) values ('ORDER_BOOK', 'DEMO001', '2026-07-16', 7, 'OPEN')
                """
        );
        execute(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, revision)
                values ('DEMO001', true, 'OPEN', 1)
                """
        );
        execute(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date, revision
                ) values ('DEFAULT', '2026-07-16', null, '2026-07-16', 1)
                """
        );
        execute(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at, revision
                ) values ('DEFAULT', '2026-07-16', 7200, 1800, false, null, now(6), 1)
                """
        );

        try (Connection orderTransaction = transactionConnection()) {
            long lockedEpoch = querySingleLong(
                    orderTransaction,
                    """
                    select f.session_epoch
                      from stock_market_session_fence f
                      join stock_order_book_market_config c
                        on c.symbol = f.symbol
                       and c.enabled = true
                       and c.market_status = 'OPEN'
                      join stock_market_business_state b
                        on b.state_id = 'DEFAULT'
                      join stock_simulation_clock sc
                        on sc.clock_id = 'DEFAULT'
                     where f.market_type = 'ORDER_BOOK'
                       and f.symbol = 'DEMO001'
                       and f.business_date = '2026-07-16'
                       and f.session_epoch = 7
                       and f.session_state = 'OPEN'
                     for share of f
                    """
            );
            assertThat(lockedEpoch).isEqualTo(7L);
            execute(
                    orderTransaction,
                    """
                    insert into stock_order(id, account_id, market_type, symbol, status, created_at)
                    values (10, 201, 'ORDER_BOOK', 'DEMO001', 'PENDING', now(6))
                    """
            );

            CountDownLatch closeStarted = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var controlRowsUpdated = executor.submit(() -> {
                    try (Connection updater = connection(); Statement statement = updater.createStatement()) {
                        statement.execute("set session innodb_lock_wait_timeout = 2");
                        int updated = statement.executeUpdate(
                                "update stock_order_book_market_config set revision = revision + 1 "
                                        + "where symbol = 'DEMO001'"
                        );
                        updated += statement.executeUpdate(
                                "update stock_market_business_state set revision = revision + 1 "
                                        + "where state_id = 'DEFAULT'"
                        );
                        updated += statement.executeUpdate(
                                "update stock_simulation_clock set revision = revision + 1 "
                                        + "where clock_id = 'DEFAULT'"
                        );
                        return updated;
                    }
                });
                assertThat(controlRowsUpdated.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(3);

                var close = executor.submit(() -> {
                    try (Connection closeTransaction = transactionConnection()) {
                        closeStarted.countDown();
                        querySingleLong(
                                closeTransaction,
                                """
                                select session_epoch
                                  from stock_market_session_fence
                                 where market_type = 'ORDER_BOOK'
                                   and symbol = 'DEMO001'
                                 for update
                                """
                        );
                        int updated = execute(
                                closeTransaction,
                                """
                                update stock_market_session_fence
                                   set session_epoch = 8,
                                       session_state = 'CLOSING'
                                 where market_type = 'ORDER_BOOK'
                                   and symbol = 'DEMO001'
                                   and session_epoch = 7
                                   and session_state = 'OPEN'
                                """
                        );
                        closeTransaction.commit();
                        return updated;
                    }
                });

                assertThat(closeStarted.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                assertThat(close).isNotDone();

                orderTransaction.commit();
                assertThat(close.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        try (Connection nextOrder = transactionConnection()) {
            OptionalLong stalePermit = queryOptionalLong(
                    nextOrder,
                    """
                    select f.session_epoch
                      from stock_market_session_fence f
                     where f.market_type = 'ORDER_BOOK'
                       and f.symbol = 'DEMO001'
                       and f.business_date = '2026-07-16'
                       and f.session_epoch = 7
                       and f.session_state = 'OPEN'
                     for share of f
                    """
            );
            assertThat(stalePermit).isEmpty();
            nextOrder.rollback();
        }
        assertThat(querySingleLong("select count(*) from stock_order where id = 10")).isEqualTo(1L);
        assertThat(querySingleLong(
                """
                select count(*)
                  from stock_market_session_fence
                 where session_epoch = 8
                   and session_state = 'CLOSING'
                """
        )).isEqualTo(1L);
    }

    @Test
    void ownedOrderFenceLookup_locksOnlyFenceAndDoesNotPrelockHotLedgerRows() throws Exception {
        execute(
                """
                insert into stock_account(id, user_key, status, revision)
                values (101, 'owner-101', 'ACTIVE', 1)
                """
        );
        execute(
                """
                insert into stock_order(
                    id, account_id, market_type, symbol, side, status,
                    quantity, filled_quantity, created_at
                ) values (50, 101, 'ORDER_BOOK', 'DEMO001', 'SELL', 'PENDING', 10, 0, now(6))
                """
        );
        execute(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state
                ) values ('ORDER_BOOK', 'DEMO001', '2026-07-16', 7, 'OPEN')
                """
        );
        execute(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, revision)
                values ('DEMO001', true, 'OPEN', 1)
                """
        );
        execute(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date, raw_simulation_date, revision
                ) values ('DEFAULT', '2026-07-16', null, '2026-07-16', 1)
                """
        );
        execute(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at, revision
                ) values ('DEFAULT', '2026-07-16', 7200, 1800, false, null, now(6), 1)
                """
        );

        try (Connection ownedOrderLookup = transactionConnection()) {
            long lockedEpoch = querySingleLong(
                    ownedOrderLookup,
                    """
                    select f.session_epoch,
                           o.filled_quantity,
                           a.revision,
                           ob.revision,
                           b.revision,
                           sc.revision
                      from stock_order o
                      join stock_account a
                        on a.id = o.account_id
                       and a.user_key = 'owner-101'
                       and a.status = 'ACTIVE'
                      join stock_market_session_fence f
                        on f.market_type = o.market_type
                       and f.symbol = o.symbol
                      left join stock_order_book_market_config ob
                        on ob.symbol = o.symbol
                       and o.market_type = 'ORDER_BOOK'
                      left join stock_virtual_market_config vm
                        on vm.symbol = o.symbol
                       and o.market_type = 'VIRTUAL_PRICE'
                      join stock_market_business_state b
                        on b.state_id = 'DEFAULT'
                      join stock_simulation_clock sc
                        on sc.clock_id = 'DEFAULT'
                     where o.id = 50
                     for share of f
                    """
            );
            assertThat(lockedEpoch).isEqualTo(7L);

            var executor = Executors.newSingleThreadExecutor();
            try {
                var nonFenceRowsUpdated = executor.submit(() -> {
                    try (Connection updater = connection(); Statement statement = updater.createStatement()) {
                        statement.execute("set session innodb_lock_wait_timeout = 2");
                        int updated = statement.executeUpdate(
                                "update stock_order set filled_quantity = 1 where id = 50"
                        );
                        updated += statement.executeUpdate(
                                "update stock_account set revision = revision + 1 where id = 101"
                        );
                        updated += statement.executeUpdate(
                                "update stock_order_book_market_config set revision = revision + 1 "
                                        + "where symbol = 'DEMO001'"
                        );
                        updated += statement.executeUpdate(
                                "update stock_market_business_state set revision = revision + 1 "
                                        + "where state_id = 'DEFAULT'"
                        );
                        updated += statement.executeUpdate(
                                "update stock_simulation_clock set revision = revision + 1 "
                                        + "where clock_id = 'DEFAULT'"
                        );
                        return updated;
                    }
                });
                assertThat(nonFenceRowsUpdated.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                        .isEqualTo(5);

                CountDownLatch closeStarted = new CountDownLatch(1);
                var close = executor.submit(() -> {
                    try (Connection closeTransaction = transactionConnection()) {
                        closeStarted.countDown();
                        querySingleLong(
                                closeTransaction,
                                """
                                select session_epoch
                                  from stock_market_session_fence
                                 where market_type = 'ORDER_BOOK'
                                   and symbol = 'DEMO001'
                                 for update
                                """
                        );
                        closeTransaction.rollback();
                        return 1;
                    }
                });

                assertThat(closeStarted.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                assertThat(close).isNotDone();

                ownedOrderLookup.commit();
                assertThat(close.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(querySingleLong("select filled_quantity from stock_order where id = 50")).isEqualTo(1L);
        assertThat(querySingleLong("select revision from stock_account where id = 101")).isEqualTo(2L);
    }

    @Test
    void pointLockedSignalClaim_skipsLockedHeadWithoutBlockingNextSignal() throws Exception {
        execute(
                """
                insert into stock_batch_job_signal(
                    id, status, next_attempt_at, eligible_at, lease_until
                ) values (1, 'PENDING', now(6), null, null),
                         (2, 'PENDING', now(6), null, null)
                """
        );

        try (Connection firstConsumer = transactionConnection();
             Connection secondConsumer = transactionConnection()) {
            long firstId = querySingleLong(
                    firstConsumer,
                    """
                    select id
                      from stock_batch_job_signal
                     where id = 1
                       and status in ('PENDING', 'DEFERRED')
                       and next_attempt_at <= now(6)
                     for update skip locked
                    """
            );
            OptionalLong lockedHead = queryOptionalLong(
                    secondConsumer,
                    """
                    select id
                      from stock_batch_job_signal
                     where id = 1
                       and status in ('PENDING', 'DEFERRED')
                       and next_attempt_at <= now(6)
                     for update skip locked
                    """
            );
            long secondId = querySingleLong(
                    secondConsumer,
                    """
                    select id
                      from stock_batch_job_signal
                     where id = 2
                       and status in ('PENDING', 'DEFERRED')
                       and next_attempt_at <= now(6)
                     for update skip locked
                    """
            );

            assertThat(lockedHead).isEmpty();
            assertThat(firstId + ":" + secondId).isEqualTo("1:2");
            firstConsumer.rollback();
            secondConsumer.rollback();
        }
    }

    @Test
    void fundingBudgetFullConsumption_marksExhaustedWithMySqlAssignmentSemantics() throws Exception {
        execute(
                """
                insert into stock_auto_participant_funding_budget(
                    id, available_amount, reserved_amount, spent_amount, status
                ) values (1, 0.00, 100.00, 0.00, 'ACTIVE')
                """
        );
        execute(
                """
                insert into stock_auto_participant_order_budget(
                    order_id, budget_id, remaining_reserved_amount, spent_amount, released_amount
                ) values (10, 1, 100.00, 0.00, 0.00)
                """
        );
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        AutoParticipantFundingBudgetService service =
                new AutoParticipantFundingBudgetService(new JdbcTemplate(dataSource));
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transactionTemplate.executeWithoutResult(status -> service.consumeOrderBudget(
                10L,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                LocalDateTime.of(2027, 1, 18, 9, 0)
        ));

        assertThat(querySingleString(
                "select concat(available_amount, ':', reserved_amount, ':', spent_amount, ':', status) "
                        + "from stock_auto_participant_funding_budget where id = 1"
        )).isEqualTo("0.00:0.00:100.00:EXHAUSTED");
    }

    @Test
    void orderBookExecutionBatchUpdates_applyTwoAccountsAndOrdersWithMySqlCaseSemantics() throws Exception {
        execute(
                """
                insert into stock_account(id, user_key, status, cash_balance, revision)
                values (101, 'seller', 'ACTIVE', 100.00, 1),
                       (102, 'buyer', 'ACTIVE', 50.00, 1)
                """
        );
        execute(
                """
                insert into stock_order(
                    id, account_id, market_type, symbol, side, order_type, status,
                    limit_price, quantity, filled_quantity, reserved_cash, created_at
                ) values (201, 101, 'ORDER_BOOK', 'DEMO001', 'SELL', 'LIMIT', 'PENDING',
                          70.00, 1, 0, 0.00, now(6)),
                         (202, 102, 'ORDER_BOOK', 'DEMO001', 'BUY', 'LIMIT', 'PENDING',
                          70.00, 1, 0, 70.00, now(6))
                """
        );
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        OrderBookExecutionWriter writer = new OrderBookExecutionWriter(
                jdbcTemplate,
                new ExecutionHoldingJdbcSupport(jdbcTemplate),
                new StockHoldingReservationJdbcSupport(jdbcTemplate)
        );
        LocalDateTime executedAt = LocalDateTime.of(2027, 1, 18, 9, 0);

        writer.adjustMatchedAccounts(
                101L,
                new BigDecimal("70.00"),
                102L,
                new BigDecimal("-2.00"),
                executedAt
        );
        writer.updateOrdersAfterFill(
                new OrderBookOrderFillUpdate(
                        201L,
                        "FILLED",
                        1L,
                        new BigDecimal("70.00"),
                        BigDecimal.ZERO
                ),
                new OrderBookOrderFillUpdate(
                        202L,
                        "FILLED",
                        1L,
                        new BigDecimal("70.00"),
                        BigDecimal.ZERO
                ),
                executedAt
        );

        assertThat(querySingleString(
                "select concat("
                        + "(select group_concat(concat(id, ':', cash_balance) order by id) from stock_account),"
                        + "'|',"
                        + "(select group_concat(concat(id, ':', status, ':', filled_quantity, ':', "
                        + "average_fill_price, ':', reserved_cash) order by id) from stock_order)"
                        + ")"
        )).isEqualTo(
                "101:170.00,102:48.00|"
                        + "201:FILLED:1:70.00:0.00,202:FILLED:1:70.00:0.00"
        );
    }

    @Test
    void fundingBudgetRelease_concurrentConsumers_releaseReservationExactlyOnce() throws Exception {
        execute(
                """
                insert into stock_auto_participant_funding_budget(
                    id, available_amount, reserved_amount, spent_amount, status
                ) values (1, 0.00, 100.00, 0.00, 'ACTIVE')
                """
        );
        execute(
                """
                insert into stock_auto_participant_order_budget(
                    order_id, budget_id, remaining_reserved_amount, spent_amount, released_amount
                ) values (10, 1, 100.00, 0.00, 0.00)
                """
        );

        try (Connection firstConsumer = transactionConnection()) {
            long budgetId = querySingleLong(
                    firstConsumer,
                    """
                    select budget_id
                      from stock_auto_participant_order_budget
                     where order_id = 10
                       and remaining_reserved_amount > 0
                     for update
                    """
            );
            assertThat(budgetId).isEqualTo(1L);

            var executor = Executors.newSingleThreadExecutor();
            try {
                var secondRelease = executor.submit(() -> {
                    try (Connection secondConsumer = transactionConnection()) {
                        OptionalLong secondBudgetId = queryOptionalLong(
                                secondConsumer,
                                """
                                select budget_id
                                  from stock_auto_participant_order_budget
                                 where order_id = 10
                                   and remaining_reserved_amount > 0
                                 for update
                                """
                        );
                        if (secondBudgetId.isEmpty()) {
                            secondConsumer.rollback();
                            return 0;
                        }
                        int updated = execute(
                                secondConsumer,
                                """
                                update stock_auto_participant_order_budget
                                   set remaining_reserved_amount = 0,
                                       released_amount = released_amount + 100
                                 where order_id = 10
                                   and budget_id = 1
                                   and remaining_reserved_amount >= 100
                                """
                        );
                        secondConsumer.commit();
                        return updated;
                    }
                });

                Thread.sleep(200);
                assertThat(secondRelease).isNotDone();
                assertThat(execute(
                        firstConsumer,
                        """
                        update stock_auto_participant_order_budget
                           set remaining_reserved_amount = 0,
                               released_amount = released_amount + 100
                         where order_id = 10
                           and budget_id = 1
                           and remaining_reserved_amount >= 100
                        """
                )).isEqualTo(1);
                assertThat(execute(
                        firstConsumer,
                        """
                        update stock_auto_participant_funding_budget
                           set reserved_amount = reserved_amount - 100,
                               available_amount = available_amount + 100
                         where id = 1
                           and reserved_amount >= 100
                        """
                )).isEqualTo(1);
                firstConsumer.commit();

                assertThat(secondRelease.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isZero();
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(querySingleString(
                "select concat(available_amount, ':', reserved_amount) from stock_auto_participant_funding_budget where id = 1"
        )).isEqualTo("100.00:0.00");
        assertThat(querySingleString(
                "select concat(remaining_reserved_amount, ':', released_amount) from stock_auto_participant_order_budget where order_id = 10"
        )).isEqualTo("0.00:100.00");
    }

    @Test
    void fundingBudgetConsumptionAndCancellation_serializeWithoutDoubleSpendOrDoubleRelease() throws Exception {
        execute(
                """
                insert into stock_auto_participant_funding_budget(
                    id, available_amount, reserved_amount, spent_amount, status
                ) values (1, 0.00, 100.00, 0.00, 'ACTIVE')
                """
        );
        execute(
                """
                insert into stock_auto_participant_order_budget(
                    order_id, budget_id, remaining_reserved_amount, spent_amount, released_amount
                ) values (10, 1, 100.00, 0.00, 0.00)
                """
        );

        try (Connection execution = transactionConnection()) {
            assertThat(querySingleLong(
                    execution,
                    """
                    select budget_id
                      from stock_auto_participant_order_budget
                     where order_id = 10
                       and remaining_reserved_amount >= 60
                     for update
                    """
            )).isEqualTo(1L);

            var executor = Executors.newSingleThreadExecutor();
            try {
                var cancellation = executor.submit(() -> {
                    try (Connection cancel = transactionConnection()) {
                        long remaining = querySingleLong(
                                cancel,
                                """
                                select cast(remaining_reserved_amount as unsigned)
                                  from stock_auto_participant_order_budget
                                 where order_id = 10
                                 for update
                                """
                        );
                        int linkUpdated = execute(
                                cancel,
                                """
                                update stock_auto_participant_order_budget
                                   set remaining_reserved_amount = 0,
                                       released_amount = released_amount + %d
                                 where order_id = 10
                                   and budget_id = 1
                                   and remaining_reserved_amount = %d
                                """.formatted(remaining, remaining)
                        );
                        int budgetUpdated = execute(
                                cancel,
                                """
                                update stock_auto_participant_funding_budget
                                   set reserved_amount = reserved_amount - %d,
                                       available_amount = available_amount + %d
                                 where id = 1
                                   and reserved_amount >= %d
                                """.formatted(remaining, remaining, remaining)
                        );
                        cancel.commit();
                        return linkUpdated + budgetUpdated;
                    }
                });

                Thread.sleep(200);
                assertThat(cancellation).isNotDone();
                assertThat(execute(
                        execution,
                        """
                        update stock_auto_participant_order_budget
                           set remaining_reserved_amount = remaining_reserved_amount - 60,
                               spent_amount = spent_amount + 60
                         where order_id = 10
                           and budget_id = 1
                           and remaining_reserved_amount >= 60
                        """
                )).isEqualTo(1);
                assertThat(execute(
                        execution,
                        """
                        update stock_auto_participant_funding_budget
                           set reserved_amount = reserved_amount - 60,
                               spent_amount = spent_amount + 60
                         where id = 1
                           and reserved_amount >= 60
                        """
                )).isEqualTo(1);
                execution.commit();

                assertThat(cancellation.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(2);
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(querySingleString(
                "select concat(available_amount, ':', reserved_amount, ':', spent_amount) "
                        + "from stock_auto_participant_funding_budget where id = 1"
        )).isEqualTo("40.00:0.00:60.00");
        assertThat(querySingleString(
                "select concat(remaining_reserved_amount, ':', spent_amount, ':', released_amount) "
                        + "from stock_auto_participant_order_budget where order_id = 10"
        )).isEqualTo("0.00:60.00:40.00");
    }

    @Test
    void marketMakerBalancedPair_concurrentPlansCommitOneCompletePairAndNeverHalfPair() throws Exception {
        execute(
                """
                insert into stock_account(id, user_key, status, cash_balance, revision)
                values (501, 'market-maker-501', 'ACTIVE', 100.00, 1)
                """
        );
        execute(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity)
                values (501, 'DEMO001', 10, 0)
                """
        );

        try (Connection firstPlan = transactionConnection()) {
            assertThat(querySingleLong(
                    firstPlan,
                    "select cast(cash_balance as unsigned) from stock_account where id = 501 for update"
            )).isEqualTo(100L);
            assertThat(querySingleLong(
                    firstPlan,
                    "select quantity - reserved_quantity from stock_holding "
                            + "where account_id = 501 and symbol = 'DEMO001' for update"
            )).isEqualTo(10L);

            var executor = Executors.newSingleThreadExecutor();
            try {
                var competingPlan = executor.submit(() -> reserveAndInsertBalancedPair(10L, 11L));

                Thread.sleep(200);
                assertThat(competingPlan).isNotDone();
                reserveBalancedPair(firstPlan, 1L, 2L);
                firstPlan.commit();

                assertThat(competingPlan.get(FUTURE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isZero();
            } finally {
                executor.shutdownNow();
            }
        }

        assertThat(querySingleLong("select count(*) from stock_order where account_id = 501")).isEqualTo(2L);
        assertThat(querySingleString(
                "select concat(sum(side = 'BUY'), ':', sum(side = 'SELL')) "
                        + "from stock_order where account_id = 501"
        )).isEqualTo("1:1");
        assertThat(querySingleString(
                "select concat(cash_balance, ':', reserved_quantity) "
                        + "from stock_account join stock_holding on stock_holding.account_id = stock_account.id "
                        + "where stock_account.id = 501 and stock_holding.symbol = 'DEMO001'"
        )).isEqualTo("0.00:10");
    }

    private int reserveAndInsertBalancedPair(long buyOrderId, long sellOrderId) throws SQLException {
        try (Connection transaction = transactionConnection()) {
            long cash = querySingleLong(
                    transaction,
                    "select cast(cash_balance as unsigned) from stock_account where id = 501 for update"
            );
            long holding = querySingleLong(
                    transaction,
                    "select quantity - reserved_quantity from stock_holding "
                            + "where account_id = 501 and symbol = 'DEMO001' for update"
            );
            if (cash < 100L || holding < 10L) {
                transaction.rollback();
                return 0;
            }
            reserveBalancedPair(transaction, buyOrderId, sellOrderId);
            transaction.commit();
            return 2;
        }
    }

    private void reserveBalancedPair(Connection transaction, long buyOrderId, long sellOrderId) throws SQLException {
        assertThat(execute(
                transaction,
                "update stock_account set cash_balance = cash_balance - 100 where id = 501 and cash_balance >= 100"
        )).isEqualTo(1);
        assertThat(execute(
                transaction,
                "update stock_holding set reserved_quantity = reserved_quantity + 10 "
                        + "where account_id = 501 and symbol = 'DEMO001' "
                        + "and quantity - reserved_quantity >= 10"
        )).isEqualTo(1);
        assertThat(execute(
                transaction,
                """
                insert into stock_order(
                    id, account_id, market_type, symbol, side, status,
                    quantity, filled_quantity, reserved_cash, created_at
                ) values (%d, 501, 'ORDER_BOOK', 'DEMO001', 'BUY', 'PENDING', 10, 0, 100.00, now(6)),
                         (%d, 501, 'ORDER_BOOK', 'DEMO001', 'SELL', 'PENDING', 10, 0, 0.00, now(6))
                """.formatted(buyOrderId, sellOrderId)
        )).isEqualTo(2);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private Connection transactionConnection() throws SQLException {
        Connection connection = connection();
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("set session innodb_lock_wait_timeout = 5");
        }
        return connection;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, sql);
        }
    }

    private int execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private long querySingleLong(String sql) throws SQLException {
        try (Connection connection = connection()) {
            return querySingleLong(connection, sql);
        }
    }

    private String querySingleString(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private long querySingleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private OptionalLong queryOptionalLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? OptionalLong.of(resultSet.getLong(1)) : OptionalLong.empty();
        }
    }
}
