package stock.batch.service.batch.automarket.reader;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AutoParticipantCashFlowReaderTest {

    @Test
    void findRecentCashFlows_readsMultipleAccountsAndReasonsWithSingleQuery() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("auto_participant_cash_flow_reader_test")
        );
        jdbcTemplate.execute("""
                create table stock_account_cash_flow (
                    id bigint auto_increment primary key,
                    account_id bigint not null,
                    reason varchar(64) not null,
                    created_by varchar(64) not null,
                    created_at timestamp not null
                )
                """);
        AutoParticipantCashFlowReader reader = new AutoParticipantCashFlowReader(jdbcTemplate);
        LocalDateTime since = LocalDateTime.of(2026, 6, 29, 9, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 29, 9, 30);
        jdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, reason, created_by, created_at) values (?, ?, ?, ?)",
                10L,
                "AUTO_PROFILE_RECURRING_DEPOSIT",
                "AUTO_MARKET",
                createdAt
        );
        jdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, reason, created_by, created_at) values (?, ?, ?, ?)",
                20L,
                "IGNORED_REASON",
                "AUTO_MARKET",
                createdAt
        );
        jdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, reason, created_by, created_at) values (?, ?, ?, ?)",
                10L,
                "AUTO_PROFILE_RECURRING_DEPOSIT",
                "OTHER",
                createdAt
        );
        jdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, reason, created_by, created_at) values (?, ?, ?, ?)",
                20L,
                "AUTO_PARTICIPANT_RECURRING_DEPOSIT",
                "AUTO_MARKET_MANUAL",
                createdAt
        );

        List<AutoParticipantRecentCashFlow> cashFlows = reader.findRecentCashFlows(
                List.of(10L, 20L),
                Set.of("AUTO_PROFILE_RECURRING_DEPOSIT", "AUTO_PARTICIPANT_RECURRING_DEPOSIT"),
                Set.of("AUTO_MARKET", "AUTO_MARKET_MANUAL"),
                since
        );

        assertThat(cashFlows).hasSize(2);
        AutoParticipantRecentCashFlow cashFlow = cashFlows.getFirst();
        assertThat(cashFlow.accountId()).isEqualTo(10L);
        assertThat(cashFlow.reason()).isEqualTo("AUTO_PROFILE_RECURRING_DEPOSIT");
        assertThat(cashFlow.createdAt()).isEqualTo(createdAt);
    }
}
