package stock.batch.service.holdingcleanup.biz;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.holdingcleanup.writer.HoldingCleanupWriter;
import stock.batch.service.simulation.SimulationClockService;

@Service
@RequiredArgsConstructor
public class HoldingCleanupService {

    private final HoldingCleanupWriter holdingCleanupWriter;
    private final SimulationClockService simulationClockService;

    @Value("${stock.batch.holding-cleanup.retention-simulation-days:1}")
    private int retentionSimulationDays;

    @Value("${stock.batch.holding-cleanup.delete-limit:1000}")
    private int deleteLimit;

    @Transactional
    public int cleanupEmptyHoldings() {
        LocalDateTime cutoff = simulationClockService.currentMarketDateTime()
                .minusDays(Math.max(0, retentionSimulationDays));
        return holdingCleanupWriter.deleteEmptyHoldingsBefore(cutoff, deleteLimit);
    }
}
