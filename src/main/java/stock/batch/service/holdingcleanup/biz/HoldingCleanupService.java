package stock.batch.service.holdingcleanup.biz;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.holdingcleanup.writer.HoldingCleanupWriter;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;

@Service
@RequiredArgsConstructor
public class HoldingCleanupService {

    private static final int MAX_RETENTION_SIMULATION_DAYS = 36_500;
    private static final int MAX_DELETE_LIMIT = 5_000;

    private final HoldingCleanupWriter holdingCleanupWriter;
    private final SimulationClockService simulationClockService;
    private final MarketSessionFenceService marketSessionFenceService;

    @Value("${stock.batch.holding-cleanup.retention-simulation-days:1}")
    private int retentionSimulationDays;

    @Value("${stock.batch.holding-cleanup.delete-limit:1000}")
    private int deleteLimit;

    @PostConstruct
    void validateVolumeConfiguration() {
        if (retentionSimulationDays < 0 || retentionSimulationDays > MAX_RETENTION_SIMULATION_DAYS) {
            throw new IllegalStateException(
                    "stock.batch.holding-cleanup.retention-simulation-days must be between 0 and %d: %d"
                            .formatted(MAX_RETENTION_SIMULATION_DAYS, retentionSimulationDays)
            );
        }
        if (deleteLimit < 1 || deleteLimit > MAX_DELETE_LIMIT) {
            throw new IllegalStateException(
                    "stock.batch.holding-cleanup.delete-limit must be between 1 and %d: %d"
                            .formatted(MAX_DELETE_LIMIT, deleteLimit)
            );
        }
    }

    @Transactional
    public int cleanupEmptyHoldings() {
        marketSessionFenceService.acquireMarketLedgerMutationPermit("empty holding cleanup");
        LocalDateTime cutoff = simulationClockService.currentMarketDateTime()
                .minusDays(retentionSimulationDays);
        return holdingCleanupWriter.deleteEmptyHoldingsBefore(cutoff, deleteLimit);
    }
}
