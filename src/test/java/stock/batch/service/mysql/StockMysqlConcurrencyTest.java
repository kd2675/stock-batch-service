package stock.batch.service.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

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
            statement.execute("drop table if exists stock_order");
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
                        revision bigint not null,
                        unique key uk_stock_account_user_key (user_key)
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
                        status varchar(30) not null,
                        quantity bigint not null default 1,
                        filled_quantity bigint not null default 0,
                        reserved_cash decimal(19, 2) not null default 0,
                        created_at datetime(6) not null,
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

        try (Connection locker = transactionConnection()) {
            querySingleLong(
                    locker,
                    """
                    select id
                      from stock_order force index (primary)
                     where id in (1, 2)
                     order by id
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
            } finally {
                executor.shutdownNow();
            }
            locker.rollback();
        }

        assertThat(querySingleLong("select count(*) from stock_order where id = 3")).isEqualTo(1L);
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
    void skipLockedSignalClaim_skipsLockedHeadWithoutBlockingNextSignal() throws Exception {
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
                     where status in ('PENDING', 'DEFERRED')
                       and next_attempt_at <= now(6)
                     order by next_attempt_at, id
                     limit 1
                     for update skip locked
                    """
            );
            long secondId = querySingleLong(
                    secondConsumer,
                    """
                    select id
                      from stock_batch_job_signal
                     where status in ('PENDING', 'DEFERRED')
                       and next_attempt_at <= now(6)
                     order by next_attempt_at, id
                     limit 1
                     for update skip locked
                    """
            );

            assertThat(firstId + ":" + secondId).isEqualTo("1:2");
            firstConsumer.rollback();
            secondConsumer.rollback();
        }
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
