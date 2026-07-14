package stock.batch.service.automarket.biz;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import web.common.core.simulation.SimulationClockSnapshot;

import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Service
@RequiredArgsConstructor
public class AutoMarketDailyRegimePreCreateService {

    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final AutoMarketReader autoMarketReader;
    private final AutoMarketDailyRegimeService autoMarketDailyRegimeService;

    @Value("${stock.batch.auto-market.daily-regime.pre-create-before-minutes:30}")
    private int preCreateBeforeMinutes;

    public int preCreateDailyRegimes() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (!shouldPreCreateDailyRegimes(clock)) {
            return 0;
        }
        var configs = autoMarketReader.findDailyRegimePreCreateConfigs();
        return autoMarketDailyRegimeService.ensureFirstSlotDailyRegimes(
                configs,
                clock.simulationDateTime().toLocalDate(),
                clock.simulationDateTime()
        );
    }

    public boolean shouldPreCreateDailyRegimes() {
        return shouldPreCreateDailyRegimes(simulationClockService.currentSnapshot());
    }

    private boolean shouldPreCreateDailyRegimes(SimulationClockSnapshot clock) {
        return clock.running() && isPreCreateWindow(clock.simulationDateTime());
    }

    private boolean isPreCreateWindow(LocalDateTime simulationDateTime) {
        if (simulationDateTime == null) {
            return false;
        }
        LocalTime openTime = simulationMarketSessionService.openTime();
        LocalTime preCreateStartTime = openTime.minus(preCreateBeforeDuration());
        LocalTime currentTime = simulationDateTime.toLocalTime();
        return !currentTime.isBefore(preCreateStartTime) && currentTime.isBefore(openTime);
    }

    private Duration preCreateBeforeDuration() {
        return Duration.ofMinutes(Math.max(1, preCreateBeforeMinutes));
    }
}
