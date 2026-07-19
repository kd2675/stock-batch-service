package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.simulation.SimulationMarketSessionService;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MarketSessionFenceServiceLedgerMutationPermitTest {

    private final DataSource dataSource = BatchTestDatabaseFactory.createDataSource(
            "market_session_ledger_mutation_permit_test"
    );
    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    private final TransactionTemplate transaction = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource)
    );
    private final MarketSessionFenceService fenceService = new MarketSessionFenceService(
            jdbcTemplate,
            mock(SimulationMarketSessionService.class),
            new SimpleMeterRegistry(),
            30
    );

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("drop table if exists stock_post_close_cycle");
        jdbcTemplate.execute("drop table if exists stock_market_session_fence");
        jdbcTemplate.execute("drop table if exists stock_order_book_market_config");
        jdbcTemplate.execute("drop table if exists stock_virtual_market_config");
        jdbcTemplate.execute("drop table if exists stock_market_business_state");
        jdbcTemplate.execute(
                """
                create table stock_market_business_state(
                  state_id varchar(20) primary key,
                  active_business_date date not null,
                  preparing_business_date date,
                  raw_simulation_date date not null,
                  version bigint not null,
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_post_close_cycle(
                  business_date date not null,
                  scope_type varchar(30) not null,
                  scope_key varchar(30) not null,
                  phase varchar(50) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order_book_market_config(
                  symbol varchar(20) primary key,
                  enabled boolean not null,
                  market_status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_virtual_market_config(
                  symbol varchar(20) primary key,
                  enabled boolean not null,
                  market_status varchar(20) not null
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_market_session_fence(
                  market_type varchar(20) not null,
                  symbol varchar(20) not null,
                  session_state varchar(20) not null,
                  primary key(market_type, symbol)
                )
                """
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 18, 10);
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date,
                    raw_simulation_date, version, created_at, updated_at
                ) values ('DEFAULT', ?, null, ?, 0, ?, ?)
                """,
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 15),
                now,
                now
        );
    }

    @Test
    void acquireMarketLedgerMutationPermit_closedAndFrozenLedgerComplete_allowsMutation() {
        assertThatCode(() -> transaction.executeWithoutResult(
                status -> fenceService.acquireMarketLedgerMutationPermit("corporate action")
        )).doesNotThrowAnyException();
    }

    @Test
    void acquireMarketLedgerMutationPermit_marketOpenedAfterStageGate_rejectsChunk() {
        jdbcTemplate.update(
                "insert into stock_order_book_market_config(symbol, enabled, market_status) values ('DEMO001', true, 'OPEN')"
        );

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> fenceService.acquireMarketLedgerMutationPermit("corporate action")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("while any enabled market is open");
    }

    @Test
    void acquireMarketLedgerMutationPermit_closeRequested_rejectsChunk() {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(business_date, scope_type, scope_key, phase)
                values (date '2026-07-15', 'FULL_MARKET', 'ALL', 'CLOSE_REQUESTED')
                """
        );

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> fenceService.acquireMarketLedgerMutationPermit("recurring cash")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger freeze is in progress");
    }

    @Test
    void acquireLiveMarketDataMutationPermit_regularOpenMarket_allowsPriceWrite() {
        jdbcTemplate.update(
                "insert into stock_virtual_market_config(symbol, enabled, market_status) values ('005930', true, 'OPEN')"
        );

        assertThatCode(() -> transaction.executeWithoutResult(
                status -> fenceService.acquireLiveMarketDataMutationPermit("compatibility market data refresh")
        )).doesNotThrowAnyException();
    }

    @Test
    void acquireLiveMarketDataMutationPermit_closeRequested_rejectsPriceWrite() {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(business_date, scope_type, scope_key, phase)
                values (date '2026-07-15', 'FULL_MARKET', 'ALL', 'CLOSE_REQUESTED')
                """
        );

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> fenceService.acquireLiveMarketDataMutationPermit("compatibility market data refresh")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger freeze is in progress");
    }
}
