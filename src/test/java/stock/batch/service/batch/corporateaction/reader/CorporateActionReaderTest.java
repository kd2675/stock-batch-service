package stock.batch.service.batch.corporateaction.reader;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

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
}
