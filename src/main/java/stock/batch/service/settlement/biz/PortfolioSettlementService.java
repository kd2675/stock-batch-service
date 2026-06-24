package stock.batch.service.settlement.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;
import stock.batch.service.batch.settlement.reader.AccountSettlementTargetReader;
import stock.batch.service.batch.settlement.writer.PortfolioSnapshotWriter;

@Service
@RequiredArgsConstructor
public class PortfolioSettlementService {

    private final AccountSettlementTargetReader targetReader;
    private final PortfolioSnapshotProcessor processor;
    private final PortfolioSnapshotWriter writer;

    @Transactional
    public int settleToday() {
        int settledCount = 0;
        for (AccountSettlementTarget target : targetReader.readTargets()) {
            writer.write(processor.process(target));
            settledCount++;
        }
        return settledCount;
    }
}
