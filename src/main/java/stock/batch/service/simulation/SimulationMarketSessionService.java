package stock.batch.service.simulation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import web.common.core.simulation.SimulationMarketSession;
import web.common.core.simulation.SimulationMarketSessions;

@Service
public class SimulationMarketSessionService {

    private final SimulationClockService simulationClockService;
    private final LocalTime openTime;
    private final LocalTime closeTime;

    public SimulationMarketSessionService(
            SimulationClockService simulationClockService,
            @Value("${stock.market-session.open-time:06:00}") String openTimeValue,
            @Value("${stock.market-session.close-time:18:00}") String closeTimeValue
    ) {
        this.simulationClockService = simulationClockService;
        this.openTime = parseTime(openTimeValue, LocalTime.of(6, 0));
        this.closeTime = parseTime(closeTimeValue, LocalTime.of(18, 0));
        if (!openTime.isBefore(closeTime)) {
            throw new IllegalArgumentException("stock.market-session.open-time must be before close-time");
        }
    }

    public SimulationMarketSession currentSession() {
        return sessionAt(simulationClockService.currentSnapshot().simulationDateTime());
    }

    public SimulationMarketSession sessionAt(LocalDateTime simulationDateTime) {
        if (simulationDateTime == null) {
            throw new IllegalArgumentException("simulationDateTime is required");
        }
        return SimulationMarketSessions.resolve(simulationDateTime, openTime, closeTime);
    }

    public boolean isRegularSession() {
        return currentSession() == SimulationMarketSession.REGULAR;
    }

    public boolean isAfterCloseSession() {
        return currentSession() == SimulationMarketSession.AFTER_CLOSE;
    }

    public LocalDate currentSimulationDate() {
        return simulationClockService.currentDate();
    }

    public LocalDate baseSimulationDate() {
        return simulationClockService.baseSimulationDate();
    }

    public LocalDateTime currentSimulationDateTime() {
        return simulationClockService.currentMarketDateTime();
    }

    public LocalTime openTime() {
        return openTime;
    }

    public LocalTime closeTime() {
        return closeTime;
    }

    private LocalTime parseTime(String value, LocalTime defaultValue) {
        return value == null || value.isBlank() ? defaultValue : LocalTime.parse(value);
    }
}
