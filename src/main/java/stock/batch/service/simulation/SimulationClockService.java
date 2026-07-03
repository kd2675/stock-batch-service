package stock.batch.service.simulation;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationClockSnapshots;

@Service
@RequiredArgsConstructor
public class SimulationClockService {

    static final String DEFAULT_CLOCK_ID = "DEFAULT";

    private final JdbcClient jdbcClient;

    @Value("${stock.simulation-clock.base-date:}")
    private String baseDateValue;

    @Value("${stock.simulation-clock.real-seconds-per-day:7200}")
    private int realSecondsPerSimulationDay;

    @Value("${stock.simulation-clock.stale-after-seconds:30}")
    private long staleAfterSeconds;

    @Transactional
    public void start() {
        SimulationClockRow row = findClock().orElseGet(this::createPausedClock);
        LocalDateTime now = maxDateTime(LocalDateTime.now(), row.lastHeartbeatAt());
        long accumulatedRealSeconds = row.accumulatedRealSeconds();
        if (row.running() && row.lastStartedAt() != null && row.lastHeartbeatAt() != null) {
            accumulatedRealSeconds += Math.max(0, Duration.between(row.lastStartedAt(), row.lastHeartbeatAt()).toSeconds());
        }
        jdbcClient.sql(
                        """
                        update stock_simulation_clock
                           set accumulated_real_seconds = ?,
                               running = true,
                               last_started_at = ?,
                               last_heartbeat_at = ?,
                               real_seconds_per_simulation_day = ?,
                               updated_at = ?
                         where clock_id = ?
                        """
                )
                .param(accumulatedRealSeconds)
                .param(now)
                .param(now)
                .param(realSecondsPerSimulationDay)
                .param(now)
                .param(DEFAULT_CLOCK_ID)
                .update();
    }

    @Transactional
    public void heartbeat() {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql(
                        """
                        update stock_simulation_clock
                           set last_heartbeat_at = case
                                   when last_heartbeat_at is null or last_heartbeat_at < ? then ?
                                   else last_heartbeat_at
                               end,
                               updated_at = case
                                   when updated_at < ? then ?
                                   else updated_at
                               end
                         where clock_id = ?
                           and running = true
                        """
                )
                .param(now)
                .param(now)
                .param(now)
                .param(now)
                .param(DEFAULT_CLOCK_ID)
                .update();
    }

    @Transactional
    public void stop() {
        SimulationClockRow row = findClock().orElse(null);
        if (row == null || !row.running() || row.lastStartedAt() == null) {
            return;
        }
        LocalDateTime now = maxDateTime(LocalDateTime.now(), row.lastHeartbeatAt());
        LocalDateTime effectiveNow = effectiveRealDateTime(row, now);
        long accumulatedRealSeconds = row.accumulatedRealSeconds()
                + Math.max(0, Duration.between(row.lastStartedAt(), effectiveNow).toSeconds());
        jdbcClient.sql(
                        """
                        update stock_simulation_clock
                           set accumulated_real_seconds = ?,
                               running = false,
                               last_heartbeat_at = ?,
                               updated_at = ?
                         where clock_id = ?
                        """
                )
                .param(accumulatedRealSeconds)
                .param(effectiveNow)
                .param(now)
                .param(DEFAULT_CLOCK_ID)
                .update();
    }

    @Transactional
    public LocalDate currentDate() {
        return currentSnapshot().simulationDate();
    }

    @Transactional
    public LocalDateTime currentMarketDateTime() {
        return currentSnapshot().simulationDateTime();
    }

    @Transactional
    public LocalDateTime currentRealDateTime() {
        return currentMarketDateTime();
    }

    @Transactional
    public SimulationClockSnapshot currentSnapshot() {
        SimulationClockRow row = findClock().orElseGet(this::createPausedClock);
        return toSnapshot(row, LocalDateTime.now());
    }

    private SimulationClockSnapshot toSnapshot(SimulationClockRow row, LocalDateTime now) {
        return SimulationClockSnapshots.calculate(
                row.baseSimulationDate(),
                row.realSecondsPerSimulationDay(),
                row.accumulatedRealSeconds(),
                row.running(),
                row.lastStartedAt(),
                row.lastHeartbeatAt(),
                staleAfterSeconds,
                now
        );
    }

    private LocalDateTime effectiveRealDateTime(SimulationClockRow row, LocalDateTime now) {
        return SimulationClockSnapshots.effectiveRealDateTime(row.lastHeartbeatAt(), staleAfterSeconds, now);
    }

    private LocalDateTime maxDateTime(LocalDateTime first, LocalDateTime second) {
        if (second == null || first.isAfter(second)) {
            return first;
        }
        return second;
    }

    private java.util.Optional<SimulationClockRow> findClock() {
        return jdbcClient.sql(
                        """
                        select clock_id,
                               base_simulation_date,
                               real_seconds_per_simulation_day,
                               accumulated_real_seconds,
                               running,
                               last_started_at,
                               last_heartbeat_at
                          from stock_simulation_clock
                         where clock_id = ?
                        """
                )
                .param(DEFAULT_CLOCK_ID)
                .query((rs, rowNum) -> new SimulationClockRow(
                        rs.getString("clock_id"),
                        rs.getObject("base_simulation_date", LocalDate.class),
                        rs.getInt("real_seconds_per_simulation_day"),
                        rs.getLong("accumulated_real_seconds"),
                        rs.getBoolean("running"),
                        rs.getObject("last_started_at", LocalDateTime.class),
                        rs.getObject("last_heartbeat_at", LocalDateTime.class)
                ))
                .optional();
    }

    private SimulationClockRow createPausedClock() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate baseSimulationDate = initialBaseDate();
        try {
            jdbcClient.sql(
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
                            values (?, ?, ?, 0, false, null, null, 'Asia/Seoul', ?, ?)
                            """
                    )
                    .param(DEFAULT_CLOCK_ID)
                    .param(baseSimulationDate)
                    .param(realSecondsPerSimulationDay)
                    .param(now)
                    .param(now)
                    .update();
        } catch (DuplicateKeyException ignored) {
            return findClock().orElseThrow();
        }
        return new SimulationClockRow(DEFAULT_CLOCK_ID, baseSimulationDate, realSecondsPerSimulationDay, 0, false, null, null);
    }

    private LocalDate initialBaseDate() {
        return baseDateValue == null || baseDateValue.isBlank()
                ? LocalDate.now()
                : LocalDate.parse(baseDateValue);
    }

    private record SimulationClockRow(
            String clockId,
            LocalDate baseSimulationDate,
            int realSecondsPerSimulationDay,
            long accumulatedRealSeconds,
            boolean running,
            LocalDateTime lastStartedAt,
            LocalDateTime lastHeartbeatAt
    ) {
    }
}
