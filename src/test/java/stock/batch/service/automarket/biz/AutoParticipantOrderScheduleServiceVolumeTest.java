package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

import static org.assertj.core.api.Assertions.assertThat;

class AutoParticipantOrderScheduleServiceVolumeTest {

    private CountingJdbcTemplate jdbcTemplate;
    private AutoParticipantOrderScheduleService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:auto-participant-order-schedule-volume;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        );
        jdbcTemplate = new CountingJdbcTemplate(dataSource);
        jdbcTemplate.execute("drop all objects");
        jdbcTemplate.execute(
                """
                create table stock_auto_participant_order_schedule (
                    user_key varchar(64) not null primary key,
                    profile_type varchar(40) not null,
                    next_run_at timestamp not null,
                    last_run_at timestamp null,
                    lease_until timestamp null,
                    lease_owner varchar(80) null,
                    run_interval_seconds int not null,
                    priority int not null,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """
        );
        service = new AutoParticipantOrderScheduleService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "baseIntervalSeconds", 10);
        ReflectionTestUtils.setField(service, "minIntervalSeconds", 3);
        ReflectionTestUtils.setField(service, "maxIntervalSeconds", 300);
        ReflectionTestUtils.setField(service, "spreadSeconds", 10);
        ReflectionTestUtils.setField(service, "leaseSeconds", 120);
        ReflectionTestUtils.setField(service, "dueLimitPerSymbol", 100);
    }

    @Test
    void ensureSchedules_501Participants_usesBoundedMultiRowStatements() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 8, 0);
        List<AutoParticipantStrategy> strategies = IntStream.rangeClosed(1, 501)
                .mapToObj(index -> strategy("stock-auto-%03d".formatted(index), index))
                .toList();
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                new NoiseTraderBehavior().defaultPolicy()
        );

        jdbcTemplate.resetUpdateCount();
        int scheduled = service.ensureSchedules(strategies, policies, now);

        assertThat(List.of(
                (long) scheduled,
                (long) jdbcTemplate.updateCount(),
                scheduleCount()
        )).containsExactly(501L, 6L, 501L);
    }

    @Test
    void ensureSchedules_100ChangedParticipants_usesOneMetadataUpdate() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 8, 0);
        List<AutoParticipantStrategy> strategies = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> strategy("stock-auto-%03d".formatted(index), index))
                .toList();
        insertDueSchedules(strategies, now.minusMinutes(1));
        jdbcTemplate.update(
                "update stock_auto_participant_order_schedule set run_interval_seconds = 999, priority = 1"
        );
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                new NoiseTraderBehavior().defaultPolicy()
        );

        jdbcTemplate.resetUpdateCount();
        int scheduled = service.ensureSchedules(strategies, policies, now);

        assertThat(List.of(
                (long) scheduled,
                (long) jdbcTemplate.updateCount(),
                correctedScheduleCount()
        )).containsExactly(0L, 1L, 100L);
    }

    @Test
    void completeStrategies_100Participants_usesOneBoundedUpdateStatement() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        List<AutoParticipantStrategy> strategies = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> strategy("stock-auto-%03d".formatted(index), index))
                .toList();
        insertDueSchedules(strategies, now.minusMinutes(1));
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                new NoiseTraderBehavior().defaultPolicy()
        );
        List<AutoParticipantStrategy> claimed = service.claimDueStrategies(
                strategies,
                policies,
                now,
                false
        );

        jdbcTemplate.resetUpdateCount();
        int completed = service.completeStrategies(claimed, policies, now);

        assertThat(List.of(
                (long) completed,
                (long) jdbcTemplate.updateCount(),
                activeLeaseCount(),
                completedScheduleCount(now)
        )).containsExactly(100L, 1L, 0L, 100L);
    }

    private AutoParticipantStrategy strategy(String userKey, long accountId) {
        return new AutoParticipantStrategy(
                userKey,
                accountId,
                5,
                AutoParticipantProfileType.NOISE_TRADER
        );
    }

    private void insertDueSchedules(List<AutoParticipantStrategy> strategies, LocalDateTime dueAt) {
        jdbcTemplate.batchUpdate(
                """
                insert into stock_auto_participant_order_schedule(
                    user_key, profile_type, next_run_at, last_run_at,
                    lease_until, lease_owner, run_interval_seconds, priority, created_at, updated_at
                ) values (?, 'NOISE_TRADER', ?, null, null, null, 10, 50, current_timestamp, current_timestamp)
                """,
                strategies,
                strategies.size(),
                (statement, strategy) -> {
                    statement.setString(1, strategy.userKey());
                    statement.setObject(2, dueAt);
                }
        );
    }

    private long activeLeaseCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from stock_auto_participant_order_schedule where lease_owner is not null",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private long completedScheduleCount(LocalDateTime now) {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_auto_participant_order_schedule
                 where last_run_at = ?
                   and next_run_at > ?
                """,
                Long.class,
                now,
                now
        );
        return count == null ? 0L : count;
    }

    private long scheduleCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from stock_auto_participant_order_schedule",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private long correctedScheduleCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_auto_participant_order_schedule
                 where run_interval_seconds <> 999
                   and priority <> 1
                """,
                Long.class
        );
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
