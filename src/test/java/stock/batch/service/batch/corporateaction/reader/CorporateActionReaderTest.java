package stock.batch.service.batch.corporateaction.reader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorporateActionReaderTest {

    @Test
    void findEventProfilePolicies_unknownProfileType_failsClosed() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("corporate_action_event_profile_reader_test")
        );
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_event_profile_config(
                  profile_type varchar(40) primary key,
                  shareholder_subscription_rate decimal(8,4) not null,
                  public_offering_subscription_rate decimal(8,4) not null,
                  max_cash_allocation_rate decimal(8,4) not null,
                  updated_at timestamp not null
                )
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_event_profile_config(
                  profile_type, shareholder_subscription_rate, public_offering_subscription_rate,
                  max_cash_allocation_rate, updated_at
                ) values ('LONG_TERM_HODLER', 0.95, 0.35, 0.40, ?)
                """,
                LocalDateTime.now()
        );
        CorporateActionReader reader = new CorporateActionReader(jdbcTemplate);

        assertThatThrownBy(reader::findEventProfilePolicies)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown auto participant event profile type: LONG_TERM_HODLER");
    }

    @Test
    void findOpenCapitalIncreaseSubscriptions_completedActionAndLimit_returnsNextBoundedAction() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("corporate_action_bounded_reader_test")
        );
        jdbcTemplate.execute(
                """
                create table stock_corporate_action(
                  id bigint primary key,
                  symbol varchar(20) not null,
                  action_type varchar(40) not null,
                  status varchar(40) not null,
                  offering_type varchar(40),
                  share_quantity bigint,
                  issue_price decimal(19,4),
                  subscription_start_date date,
                  subscription_end_date date
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_corporate_action_processing(
                  action_id bigint not null,
                  account_scope_key varchar(80) not null,
                  action_phase varchar(80) not null,
                  effective_business_date date not null,
                  status varchar(40) not null
                )
                """
        );
        LocalDate today = LocalDate.of(2026, 7, 15);
        for (long actionId = 1L; actionId <= 3L; actionId++) {
            jdbcTemplate.update(
                    """
                    insert into stock_corporate_action(
                      id, symbol, action_type, status, offering_type, share_quantity, issue_price,
                      subscription_start_date, subscription_end_date
                    ) values (?, ?, 'PAID_IN_CAPITAL_INCREASE', 'ANNOUNCED',
                              'PUBLIC_OFFERING', 100, ?, ?, ?)
                    """,
                    actionId,
                    "ZQ" + actionId,
                    new BigDecimal("1000.00"),
                    today.minusDays(1),
                    today.plusDays(1)
            );
        }
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_processing(
                  action_id, account_scope_key, action_phase, effective_business_date, status
                ) values (1, 'ALL', 'public-offering-auto-subscription', ?, 'COMPLETED')
                """,
                today
        );
        CorporateActionReader reader = new CorporateActionReader(jdbcTemplate);

        List<Long> actionIds = reader.findOpenCapitalIncreaseSubscriptions(
                        today,
                        "PAID_IN_CAPITAL_INCREASE",
                        List.of("ANNOUNCED", "EX_RIGHTS_APPLIED"),
                        1
                ).stream()
                .map(action -> action.id())
                .toList();

        assertThat(actionIds).containsExactly(2L);
    }
}
