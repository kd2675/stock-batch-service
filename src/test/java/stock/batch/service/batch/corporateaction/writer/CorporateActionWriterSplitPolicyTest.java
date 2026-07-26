package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class CorporateActionWriterSplitPolicyTest {

    private static final LocalDateTime SPLIT_AT = LocalDateTime.of(2027, 2, 1, 5, 30);

    private JdbcTemplate jdbcTemplate;
    private CorporateActionWriter writer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = BatchTestDatabaseFactory.createDataSource("split_quantity_policy");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        seedPolicies();
        writer = new CorporateActionWriter(jdbcTemplate);
    }

    @Test
    void multiplyAutomaticMarketQuantitiesForSplit_preservesEconomicScaleAcrossRoleEngines() {
        writer.multiplyAutomaticMarketQuantitiesForSplit("DEMO001", 5, SPLIT_AT);

        assertThat(queryLong(
                "select reference_daily_volume from stock_institution_symbol_mandate"
        )).isEqualTo(15_000L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select max_order_quantity, reference_daily_volume,
                       target_inventory_quantity, inventory_band_quantity
                  from stock_liquidity_mandate
                """
        )).containsEntry("MAX_ORDER_QUANTITY", 150L)
                .containsEntry("REFERENCE_DAILY_VOLUME", 15_000L)
                .containsEntry("TARGET_INVENTORY_QUANTITY", 2_500L)
                .containsEntry("INVENTORY_BAND_QUANTITY", 2_500L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select issue_price, stabilization_quantity_limit
                  from stock_underwriting_contract
                 where id = 1
                """
        )).containsEntry("ISSUE_PRICE", new BigDecimal("14000.00"))
                .containsEntry("STABILIZATION_QUANTITY_LIMIT", 5_000L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select issue_price, stabilization_quantity_limit
                  from stock_underwriting_contract
                 where id = 2
                """
        )).containsEntry("ISSUE_PRICE", new BigDecimal("70000.00"))
                .containsEntry("STABILIZATION_QUANTITY_LIMIT", 1_000L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select reference_daily_volume, submission_quantity_limit,
                       submitted_quantity, submission_amount_limit, submitted_amount
                  from stock_underwriting_daily_supply_state
                 where underwriting_contract_id = 1
                """
        )).containsEntry("REFERENCE_DAILY_VOLUME", 15_000L)
                .containsEntry("SUBMISSION_QUANTITY_LIMIT", 1_500L)
                .containsEntry("SUBMITTED_QUANTITY", 500L)
                .containsEntry("SUBMISSION_AMOUNT_LIMIT", new BigDecimal("21000000.00"))
                .containsEntry("SUBMITTED_AMOUNT", new BigDecimal("7000000.00"));
        assertThat(jdbcTemplate.queryForMap(
                """
                select reference_daily_volume, submission_quantity_limit, submitted_quantity
                  from stock_underwriting_daily_supply_state
                 where underwriting_contract_id = 2
                """
        )).containsEntry("REFERENCE_DAILY_VOLUME", 3_000L)
                .containsEntry("SUBMISSION_QUANTITY_LIMIT", 300L)
                .containsEntry("SUBMITTED_QUANTITY", 100L);
    }

    private void createSchema() {
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute("""
                create table stock_institution_symbol_mandate(
                    id bigint primary key,
                    symbol varchar(20) not null,
                    reference_daily_volume bigint not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_liquidity_mandate(
                    id bigint primary key,
                    symbol varchar(20) not null,
                    max_order_quantity bigint not null,
                    reference_daily_volume bigint not null,
                    target_inventory_quantity bigint not null,
                    inventory_band_quantity bigint not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_underwriting_contract(
                    id bigint primary key,
                    symbol varchar(20) not null,
                    issue_price decimal(19,2) not null,
                    stabilization_quantity_limit bigint not null,
                    status varchar(20) not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_underwriting_daily_supply_state(
                    simulation_trade_date date not null,
                    underwriting_contract_id bigint not null,
                    reference_daily_volume bigint not null,
                    submission_quantity_limit bigint not null,
                    submission_amount_limit decimal(19,2) not null,
                    submitted_quantity bigint not null,
                    submitted_amount decimal(19,2) not null,
                    updated_at timestamp not null,
                    primary key (simulation_trade_date, underwriting_contract_id)
                )
                """);
    }

    private void seedPolicies() {
        jdbcTemplate.update(
                """
                insert into stock_institution_symbol_mandate(
                    id, symbol, reference_daily_volume, updated_at
                ) values (1, 'DEMO001', 3000, ?)
                """,
                SPLIT_AT.minusDays(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_mandate(
                    id, symbol, max_order_quantity, reference_daily_volume,
                    target_inventory_quantity, inventory_band_quantity, updated_at
                ) values (1, 'DEMO001', 30, 3000, 500, 500, ?)
                """,
                SPLIT_AT.minusDays(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    id, symbol, issue_price, stabilization_quantity_limit, status, updated_at
                ) values (1, 'DEMO001', 70000, 1000, 'STABILIZING', ?)
                """,
                SPLIT_AT.minusDays(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    id, symbol, issue_price, stabilization_quantity_limit, status, updated_at
                ) values (2, 'DEMO001', 70000, 1000, 'COMPLETED', ?)
                """,
                SPLIT_AT.minusDays(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_daily_supply_state(
                    simulation_trade_date, underwriting_contract_id,
                    reference_daily_volume, submission_quantity_limit,
                    submission_amount_limit, submitted_quantity, submitted_amount, updated_at
                ) values ('2027-01-31', 1, 3000, 300, 21000000, 100, 7000000, ?)
                """,
                SPLIT_AT.minusDays(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_daily_supply_state(
                    simulation_trade_date, underwriting_contract_id,
                    reference_daily_volume, submission_quantity_limit,
                    submission_amount_limit, submitted_quantity, submitted_amount, updated_at
                ) values ('2027-01-31', 2, 3000, 300, 21000000, 100, 7000000, ?)
                """,
                SPLIT_AT.minusDays(1)
        );
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
