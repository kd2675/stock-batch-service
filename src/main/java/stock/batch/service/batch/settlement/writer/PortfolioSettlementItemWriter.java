package stock.batch.service.batch.settlement.writer;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;

public class PortfolioSettlementItemWriter implements ItemWriter<PortfolioSnapshotCommand> {

    private final PortfolioSnapshotWriter portfolioSnapshotWriter;
    private final LocalDate snapshotDate;
    private final LocalDateTime snapshotAt;

    public PortfolioSettlementItemWriter(
            PortfolioSnapshotWriter portfolioSnapshotWriter,
            LocalDate snapshotDate,
            LocalDateTime snapshotAt
    ) {
        this.portfolioSnapshotWriter = portfolioSnapshotWriter;
        this.snapshotDate = snapshotDate;
        this.snapshotAt = snapshotAt;
    }

    @Override
    public void write(Chunk<? extends PortfolioSnapshotCommand> chunk) {
        for (PortfolioSnapshotCommand command : chunk) {
            portfolioSnapshotWriter.write(command, snapshotDate, snapshotAt);
        }
    }
}
