package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.common.biz.StockBatchJobService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.settlement", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PortfolioSettlementScheduler {

    private final StockBatchJobService stockBatchJobService;

    @Scheduled(
            cron = "${stock.batch.settlement.cron:0 40 15 * * MON-FRI}",
            zone = "${stock.batch.settlement.zone:Asia/Seoul}"
    )
    public void settlePortfolios() {
        stockBatchJobService.settlePortfolios();
    }
}
