package stock.batch.service.marketclose.biz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.batch.corporateaction.reader.CorporateActionReader;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleKind;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostCloseReadinessServiceTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/stock/batch/service/marketclose/biz/PostCloseReadinessService.java"
    );
    private static final String HOT_LEDGER_SQL_PATTERN =
            "(?i)\\b(?:from|join|update|insert\\s+into|delete\\s+from)\\s+"
                    + "stock_(?:order|execution|holding)\\b";

    private JdbcTemplate jdbcTemplate;
    private PostCloseCycleService postCloseCycleService;
    private CorporateActionReader corporateActionReader;
    private AutoMarketProfileQueueReconcileService profileQueueReconcileService;
    private PostCloseReadinessService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        postCloseCycleService = mock(PostCloseCycleService.class);
        corporateActionReader = mock(CorporateActionReader.class);
        profileQueueReconcileService = mock(AutoMarketProfileQueueReconcileService.class);
        service = new PostCloseReadinessService(
                JdbcClient.create(dataSource),
                postCloseCycleService,
                corporateActionReader,
                profileQueueReconcileService
        );
        createSchema();
        seedReadyState();
        when(postCloseCycleService.matchesRuntimeIdentity(41L)).thenReturn(true);
        when(profileQueueReconcileService.isPreOpenQueueSynchronized()).thenReturn(true);
    }

    @Test
    void validateReadyToOpen_allBoundedChecksPass_returnsCheckCount() {
        when(postCloseCycleService.findById(41L)).thenReturn(Optional.of(cycle(PostClosePhase.AUTO_MARKET_PREPARED)));

        int checked = service.validateReadyToOpen(41L, LocalDate.of(2026, 7, 2));

        assertThat(checked).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_post_close_readiness_check where close_cycle_id = 41",
                Long.class
        )).isEqualTo(10L);
    }

    @Test
    void validateReadyToOpen_missingDailySnapshot_failsClosed() {
        when(postCloseCycleService.findById(41L)).thenReturn(Optional.of(cycle(PostClosePhase.AUTO_MARKET_PREPARED)));
        jdbcTemplate.update("delete from stock_order_book_daily_snapshot");

        assertThatThrownBy(() -> service.validateReadyToOpen(41L, LocalDate.of(2026, 7, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRICE_SNAPSHOT=1");
        assertThat(jdbcTemplate.queryForObject(
                """
                select failure_count
                  from stock_post_close_readiness_check
                 where close_cycle_id = 41
                   and check_code = 'PRICE_SNAPSHOT'
                """,
                Long.class
        )).isEqualTo(1L);
    }

    @Test
    void validateReadyToOpen_wrongPhase_rejectsBeforeDatabaseAggregation() {
        when(postCloseCycleService.findById(41L)).thenReturn(Optional.of(cycle(PostClosePhase.MARKET_DATA_PREPARED)));

        assertThatThrownBy(() -> service.validateReadyToOpen(41L, LocalDate.of(2026, 7, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTO_MARKET_PREPARED");
    }

    @Test
    void validateReadyToOpen_incompleteCorporateTransform_failsClosed() {
        LocalDate preparingBusinessDate = LocalDate.of(2026, 7, 2);
        when(postCloseCycleService.findById(41L)).thenReturn(Optional.of(cycle(PostClosePhase.AUTO_MARKET_PREPARED)));
        when(corporateActionReader.countIncompletePreOpenSecurityTransforms(preparingBusinessDate)).thenReturn(1L);

        assertThatThrownBy(() -> service.validateReadyToOpen(41L, preparingBusinessDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failures=1");
    }

    @Test
    void validateReadyToOpen_missingBusinessState_failsClosed() {
        when(postCloseCycleService.findById(41L)).thenReturn(Optional.of(cycle(PostClosePhase.AUTO_MARKET_PREPARED)));
        jdbcTemplate.update("delete from stock_market_business_state");

        assertThatThrownBy(() -> service.validateReadyToOpen(41L, LocalDate.of(2026, 7, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failures=1");
    }

    @Test
    void validateReadyToOpen_profileQueueMismatch_recordsBoundedFailure() {
        when(postCloseCycleService.findById(41L)).thenReturn(Optional.of(cycle(PostClosePhase.AUTO_MARKET_PREPARED)));
        when(profileQueueReconcileService.isPreOpenQueueSynchronized()).thenReturn(false);

        assertThatThrownBy(() -> service.validateReadyToOpen(41L, LocalDate.of(2026, 7, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTO_MARKET_PROFILE_QUEUE=1");

        assertThat(jdbcTemplate.queryForObject(
                """
                select check_status
                  from stock_post_close_readiness_check
                 where close_cycle_id = 41
                   and check_code = 'AUTO_MARKET_PROFILE_QUEUE'
                """,
                String.class
        )).isEqualTo("FAILED");
    }

    @Test
    void readinessQuery_doesNotReadOrWriteHotTradingLedgers() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertThat(source).doesNotContainPattern(HOT_LEDGER_SQL_PATTERN);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:post_close_readiness;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSchema() {
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute("""
                create table stock_market_business_state (
                    state_id varchar(20) primary key,
                    active_business_date date not null,
                    preparing_business_date date
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_virtual_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null,
                    market_status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_market_session_fence (
                    market_type varchar(20) not null,
                    symbol varchar(20) not null,
                    business_date date not null,
                    session_state varchar(20) not null,
                    primary key (market_type, symbol)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_close_price_snapshot (
                    close_cycle_id bigint not null,
                    close_run_id bigint not null,
                    symbol varchar(20) not null,
                    order_book_symbol boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_daily_snapshot (
                    close_run_id bigint not null,
                    symbol varchar(20) not null,
                    primary key (close_run_id, symbol)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_market_config (
                    symbol varchar(20) primary key,
                    enabled boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_instrument (
                    symbol varchar(20) primary key,
                    enabled boolean not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order_book_daily_regime (
                    symbol varchar(20) not null,
                    simulation_trade_date date not null,
                    regime_phase varchar(30) not null,
                    primary key (symbol, simulation_trade_date, regime_phase)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_cycle_metric (
                    close_cycle_id bigint primary key,
                    settlement_target_account_count bigint not null,
                    reconciliation_mismatch_count bigint not null,
                    settled_account_count bigint not null,
                    settlement_missing_account_count bigint not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_post_close_readiness_check (
                    close_cycle_id bigint not null,
                    check_code varchar(60) not null,
                    display_order int not null,
                    check_status varchar(20) not null,
                    failure_count bigint not null,
                    message varchar(500),
                    checked_at timestamp not null,
                    primary key (close_cycle_id, check_code),
                    unique (close_cycle_id, display_order)
                )
                """);
    }

    private void seedReadyState() {
        jdbcTemplate.update("""
                insert into stock_market_business_state(state_id, active_business_date, preparing_business_date)
                values ('DEFAULT', date '2026-07-01', date '2026-07-02')
                """);
        jdbcTemplate.update("""
                insert into stock_order_book_market_config(symbol, enabled, market_status)
                values ('DEMO001', true, 'CLOSED')
                """);
        jdbcTemplate.update("""
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_state
                ) values ('ORDER_BOOK', 'DEMO001', date '2026-07-02', 'PREPARING')
                """);
        jdbcTemplate.update("""
                insert into stock_close_price_snapshot(close_cycle_id, close_run_id, symbol, order_book_symbol)
                values (41, 91, 'DEMO001', true)
                """);
        jdbcTemplate.update("""
                insert into stock_order_book_daily_snapshot(close_run_id, symbol)
                values (91, 'DEMO001')
                """);
        jdbcTemplate.update("""
                insert into stock_auto_market_config(symbol, enabled)
                values ('DEMO001', true)
                """);
        jdbcTemplate.update("""
                insert into stock_order_book_instrument(symbol, enabled)
                values ('DEMO001', true)
                """);
        jdbcTemplate.update("""
                insert into stock_order_book_daily_regime(symbol, simulation_trade_date, regime_phase)
                values ('DEMO001', date '2026-07-02', 'SLOT_0600')
                """);
        jdbcTemplate.update("""
                insert into stock_post_close_cycle_metric(
                    close_cycle_id, settlement_target_account_count,
                    reconciliation_mismatch_count, settled_account_count,
                    settlement_missing_account_count
                ) values (41, 3, 0, 3, 0)
                """);
    }

    private PostCloseCycle cycle(PostClosePhase phase) {
        return new PostCloseCycle(
                41L,
                LocalDate.of(2026, 7, 1),
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostCloseCycleKind.TRADING,
                null,
                phase,
                PostCloseCycleStatus.PENDING,
                1,
                0,
                91L,
                LocalDateTime.of(2026, 7, 1, 18, 10),
                0,
                null,
                null,
                null
        );
    }
}
