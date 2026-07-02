package stock.batch.service.simulation;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationClockServiceTest {

    @Test
    void start_previousRunningClock_accumulatesOnlyUntilLastHeartbeat() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("simulation_clock_testdb"));
        createClockTable(jdbcTemplate);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 1, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 1, 0)
        );
        SimulationClockService service = service(jdbcTemplate);

        service.start();

        Long accumulated = jdbcTemplate.queryForObject(
                "select accumulated_real_seconds from stock_simulation_clock where clock_id = ?",
                Long.class,
                SimulationClockService.DEFAULT_CLOCK_ID
        );
        Boolean running = jdbcTemplate.queryForObject(
                "select running from stock_simulation_clock where clock_id = ?",
                Boolean.class,
                SimulationClockService.DEFAULT_CLOCK_ID
        );
        assertThat(accumulated).isEqualTo(10_800L);
        assertThat(running).isTrue();
    }

    @Test
    void currentDate_pausedClock_usesAccumulatedSimulationDays() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("simulation_clock_date_testdb"));
        createClockTable(jdbcTemplate);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 14400, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
        SimulationClockService service = service(jdbcTemplate);

        assertThat(service.currentDate()).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    void currentRealDateTime_pausedClock_usesSimulationDateTime() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("simulation_clock_real_time_testdb"));
        createClockTable(jdbcTemplate);
        LocalDateTime lastHeartbeatAt = LocalDateTime.of(2026, 1, 1, 2, 0);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 14400, false, null, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                lastHeartbeatAt,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
        SimulationClockService service = service(jdbcTemplate);

        assertThat(service.currentRealDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 3, 0, 0));
    }

    @Test
    void currentDate_runningStaleClock_accumulatesOnlyUntilLastHeartbeat() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("simulation_clock_stale_date_testdb"));
        createClockTable(jdbcTemplate);
        LocalDateTime lastStartedAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime lastHeartbeatAt = LocalDateTime.of(2000, 1, 1, 1, 0);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                lastStartedAt,
                lastHeartbeatAt,
                lastStartedAt,
                lastHeartbeatAt
        );
        SimulationClockService service = service(jdbcTemplate);

        assertThat(service.currentDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test
    void currentRealDateTime_runningStaleClock_usesSimulationDateTimeUntilLastHeartbeat() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("simulation_clock_stale_real_time_testdb"));
        createClockTable(jdbcTemplate);
        LocalDateTime lastStartedAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime lastHeartbeatAt = LocalDateTime.of(2000, 1, 1, 1, 0);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                lastStartedAt,
                lastHeartbeatAt,
                lastStartedAt,
                lastHeartbeatAt
        );
        SimulationClockService service = service(jdbcTemplate);

        assertThat(service.currentRealDateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 12, 0));
    }

    @Test
    void stop_runningStaleClock_accumulatesOnlyUntilLastHeartbeat() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(BatchTestDatabaseFactory.createDataSource("simulation_clock_stale_stop_testdb"));
        createClockTable(jdbcTemplate);
        LocalDateTime lastStartedAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime lastHeartbeatAt = LocalDateTime.of(2000, 1, 1, 1, 0);
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                values (?, ?, 7200, 7200, true, ?, ?, 'Asia/Seoul', ?, ?)
                """,
                SimulationClockService.DEFAULT_CLOCK_ID,
                LocalDate.of(2026, 1, 1),
                lastStartedAt,
                lastHeartbeatAt,
                lastStartedAt,
                lastHeartbeatAt
        );
        SimulationClockService service = service(jdbcTemplate);

        service.stop();

        Long accumulated = jdbcTemplate.queryForObject(
                "select accumulated_real_seconds from stock_simulation_clock where clock_id = ?",
                Long.class,
                SimulationClockService.DEFAULT_CLOCK_ID
        );
        Boolean running = jdbcTemplate.queryForObject(
                "select running from stock_simulation_clock where clock_id = ?",
                Boolean.class,
                SimulationClockService.DEFAULT_CLOCK_ID
        );
        LocalDateTime stoppedHeartbeatAt = jdbcTemplate.queryForObject(
                "select last_heartbeat_at from stock_simulation_clock where clock_id = ?",
                LocalDateTime.class,
                SimulationClockService.DEFAULT_CLOCK_ID
        );
        assertThat(accumulated).isEqualTo(10_800L);
        assertThat(running).isFalse();
        assertThat(stoppedHeartbeatAt).isEqualTo(lastHeartbeatAt);
    }

    private SimulationClockService service(JdbcTemplate jdbcTemplate) {
        SimulationClockService service = new SimulationClockService(JdbcClient.create(jdbcTemplate));
        ReflectionTestUtils.setField(service, "baseDateValue", "");
        ReflectionTestUtils.setField(service, "realSecondsPerSimulationDay", 7200);
        ReflectionTestUtils.setField(service, "staleAfterSeconds", 30L);
        return service;
    }

    private void createClockTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
                """
                create table stock_simulation_clock (
                  clock_id varchar(40) not null primary key,
                  base_simulation_date date not null,
                  real_seconds_per_simulation_day int not null,
                  accumulated_real_seconds bigint not null default 0,
                  running boolean not null default false,
                  last_started_at timestamp null,
                  last_heartbeat_at timestamp null,
                  timezone varchar(50) not null default 'Asia/Seoul',
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """
        );
    }
}
