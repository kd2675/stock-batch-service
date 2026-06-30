package stock.batch.service.automarket.support;

import java.time.Duration;

public final class SimulationTimeScale {

    public static final int RUNTIME_SPEED_MULTIPLIER = 24;
    public static final int MIN_RUNTIME_AUTO_ORDER_TTL_SECONDS = 3;

    private SimulationTimeScale() {
    }

    public static Duration projectDurationToRuntime(Duration projectDuration) {
        long nanos = Math.max(1L, projectDuration.toNanos() / RUNTIME_SPEED_MULTIPLIER);
        return Duration.ofNanos(nanos);
    }

    public static int projectAutoOrderTtlToRuntimeSeconds(int projectTtlSeconds) {
        return Math.max(
                MIN_RUNTIME_AUTO_ORDER_TTL_SECONDS,
                (int) Math.ceil(Math.max(1, projectTtlSeconds) / (double) RUNTIME_SPEED_MULTIPLIER)
        );
    }
}
