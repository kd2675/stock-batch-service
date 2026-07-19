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
    void findRecurringCashTargetChunk_pagesByAccountPrimaryKey() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("auto_participant_cash_target_chunk_test")
        );
        jdbcTemplate.execute("""
                create table stock_account (
                    id bigint primary key,
                    user_key varchar(64) not null,
                    status varchar(20) not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant (
                    user_key varchar(64) primary key,
                    profile_type varchar(40) not null,
                    enabled boolean not null,
                    recurring_cash_amount decimal(19,2),
                    recurring_cash_interval_value decimal(19,4),
                    recurring_cash_interval_unit varchar(20),
                    withdrawn_at timestamp
                )
                """);
        for (long accountId : List.of(30L, 10L, 20L)) {
            jdbcTemplate.update(
                    "insert into stock_account(id, user_key, status) values (?, ?, 'ACTIVE')",
                    accountId,
                    "user-" + accountId
            );
            jdbcTemplate.update(
                    """
                    insert into stock_auto_participant(
                        user_key, profile_type, enabled, recurring_cash_amount,
                        recurring_cash_interval_value, recurring_cash_interval_unit, withdrawn_at
                    ) values (?, 'PAYDAY_ACCUMULATOR', true, 1000, 1, 'DAY', null)
                    """,
                    "user-" + accountId
            );
        }
        AutoParticipantCashFlowReader reader = new AutoParticipantCashFlowReader(jdbcTemplate);

        List<Long> firstPage = reader.findRecurringCashTargetChunk(0L, 2).stream()
                .map(target -> target.accountId())
                .toList();
        List<Long> secondPage = reader.findRecurringCashTargetChunk(20L, 2).stream()
                .map(target -> target.accountId())
                .toList();

        assertThat(firstPage + ":" + secondPage).isEqualTo("[10, 20]:[30]");
    }

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
        LocalDateTime latestCreatedAt = createdAt.plusMinutes(10);
        jdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, reason, created_by, created_at) values (?, ?, ?, ?)",
                10L,
                "AUTO_PROFILE_RECURRING_DEPOSIT",
                "AUTO_MARKET_MANUAL",
                latestCreatedAt
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

        assertThat(cashFlows)
                .containsExactly(
                        new AutoParticipantRecentCashFlow(
                                10L,
                                "AUTO_PROFILE_RECURRING_DEPOSIT",
                                latestCreatedAt
                        ),
                        new AutoParticipantRecentCashFlow(
                                20L,
                                "AUTO_PARTICIPANT_RECURRING_DEPOSIT",
                                createdAt
                        )
                );
    }
}
