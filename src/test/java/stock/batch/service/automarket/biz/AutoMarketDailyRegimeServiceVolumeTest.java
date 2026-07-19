package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketDailyRegimeServiceVolumeTest {

    private CountingJdbcTemplate jdbcTemplate;
    private AutoMarketDailyRegimeService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:auto-market-regime-volume;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        );
        jdbcTemplate = new CountingJdbcTemplate(dataSource);
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute(
                """
                create table stock_order_book_daily_regime (
                    symbol varchar(20) not null,
                    simulation_trade_date date not null,
                    regime_phase varchar(20) not null,
                    price_pressure int not null,
                    asset_preference_pressure int not null,
                    volatility_pressure int not null,
                    liquidity_pressure int not null,
                    execution_aggression_pressure int not null,
                    seed bigint not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    primary key (symbol, simulation_trade_date, regime_phase)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_order_book_regime_modifier (
                    symbol varchar(20) not null,
                    simulation_trade_date date not null,
                    regime_phase varchar(20) not null,
                    modifier_window_start_at timestamp not null,
                    price_pressure int not null,
                    asset_preference_pressure int not null,
                    volatility_pressure int not null,
                    liquidity_pressure int not null,
                    execution_aggression_pressure int not null,
                    seed bigint not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    primary key (symbol, simulation_trade_date, regime_phase, modifier_window_start_at)
                )
                """
        );
        jdbcTemplate.resetUpdateCount();
        service = new AutoMarketDailyRegimeService(jdbcTemplate);
    }

    @Test
    void applyDailyRegimes_501Symbols_writesFourBoundedStatementsThenZeroOnSameWindow() {
        List<AutoMarketConfig> configs = IntStream.rangeClosed(1, 501)
                .mapToObj(index -> config("SYM%04d".formatted(index)))
                .toList();
        LocalDate businessDate = LocalDate.of(2026, 7, 15);
        LocalDateTime now = businessDate.atTime(9, 5);

        service.applyDailyRegimes(configs, businessDate, now);
        int firstWriteCount = jdbcTemplate.updateCount();
        long firstDailyCount = countRows("stock_order_book_daily_regime");
        long firstModifierCount = countRows("stock_order_book_regime_modifier");

        jdbcTemplate.resetUpdateCount();
        service.applyDailyRegimes(configs, businessDate, now);

        assertThat(List.of(
                (long) firstWriteCount,
                firstDailyCount,
                firstModifierCount,
                (long) jdbcTemplate.updateCount(),
                countRows("stock_order_book_daily_regime"),
                countRows("stock_order_book_regime_modifier")
        )).containsExactly(4L, 501L, 501L, 0L, 501L, 501L);
    }

    private AutoMarketConfig config(String symbol) {
        return new AutoMarketConfig(
                symbol,
                10,
                15,
                1_000_000L,
                BigDecimal.ONE,
                BigDecimal.valueOf(10_000),
                BigDecimal.valueOf(10_000),
                null
        );
    }

    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {

        private int updateCount;

        private CountingJdbcTemplate(DriverManagerDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            updateCount++;
            return super.update(sql, args);
        }

        private int updateCount() {
            return updateCount;
        }

        private void resetUpdateCount() {
            updateCount = 0;
        }
    }
}
