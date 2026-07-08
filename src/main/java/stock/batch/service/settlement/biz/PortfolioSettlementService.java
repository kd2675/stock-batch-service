package stock.batch.service.settlement.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;
import stock.batch.service.batch.settlement.reader.AccountSettlementTargetReader;
import stock.batch.service.batch.settlement.writer.PortfolioSnapshotWriter;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Service
@RequiredArgsConstructor
public class PortfolioSettlementService {

    private final AccountSettlementTargetReader targetReader;
    private final PortfolioSnapshotProcessor processor;
    private final PortfolioSnapshotWriter writer;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketCloseRolloverService marketCloseRolloverService;

    @Transactional
    public int settleToday() {
        LocalDate currentDate = simulationMarketSessionService.currentSimulationDate();
        if (!simulationMarketSessionService.isAfterCloseSession()
                || !marketCloseRolloverService.hasCompletedFullCloseRun(currentDate)) {
            return 0;
        }
        return settleWithWriter(writer::write);
    }

    @Transactional
    public int settle(LocalDate snapshotDate, LocalDateTime snapshotAt) {
        Objects.requireNonNull(snapshotDate, "snapshotDate is required");
        Objects.requireNonNull(snapshotAt, "snapshotAt is required");
        return settleWithWriter(command -> writer.write(command, snapshotDate, snapshotAt));
    }

    private int settleWithWriter(Consumer<PortfolioSnapshotCommand> snapshotWriter) {
        int settledCount = 0;
        for (AccountSettlementTarget target : targetReader.readTargets()) {
            snapshotWriter.accept(processor.process(target));
            settledCount++;
        }
        return settledCount;
    }
}
