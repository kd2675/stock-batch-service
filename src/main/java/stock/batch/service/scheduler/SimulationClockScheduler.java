package stock.batch.service.scheduler;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.simulation.SimulationClockService;

@Component
@RequiredArgsConstructor
@Slf4j
public class SimulationClockScheduler {

    private final SimulationClockService simulationClockService;

    @EventListener(ApplicationReadyEvent.class)
    public void startSimulationClock() {
        simulationClockService.start();
    }

    @Scheduled(fixedDelayString = "${stock.simulation-clock.heartbeat-fixed-delay-ms:5000}")
    public void heartbeat() {
        try {
            simulationClockService.heartbeat();
        } catch (RuntimeException ex) {
            log.warn("Simulation clock heartbeat failed: reason={}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void stopSimulationClock() {
        simulationClockService.stop();
    }
}
