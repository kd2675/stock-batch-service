package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.common.biz.StockBatchJobService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderExecutionScheduler {

    private final StockBatchJobService stockBatchJobService;

    @Scheduled(
            initialDelayString = "${stock.batch.execution.initial-delay-ms:35000}",
            fixedDelayString = "${stock.batch.execution.fixed-delay-ms:5000}"
    )
    public void executePendingOrders() {
        stockBatchJobService.executePendingOrders();
    }
}
