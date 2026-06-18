package stock.batch.service.common.biz;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;
import stock.batch.service.execution.biz.OrderExecutionMode;
import stock.batch.service.execution.biz.OrderExecutionService;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;
import stock.batch.service.settlement.biz.PortfolioSettlementService;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockBatchJobService {

    private static final String COMPLETED = "COMPLETED";
    private static final String SKIPPED = "SKIPPED";
    private static final String FAILED = "FAILED";
    private static final String NOT_APPLICABLE = "n/a";

    private final MarketDataRefreshService marketDataRefreshService;
    private final OrderExecutionService orderExecutionService;
    private final InternalOrderBookExecutionService internalOrderBookExecutionService;
    private final PortfolioSettlementService portfolioSettlementService;
    private final Map<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

    @Value("${stock.batch.execution.mode:virtual-market-price}")
    private String executionMode;

    @PostConstruct
    void validateExecutionMode() {
        OrderExecutionMode.fromProperty(executionMode);
    }

    public StockBatchJobRunResponse refreshMarketData() {
        return run("market-data-refresh", NOT_APPLICABLE, marketDataRefreshService::refreshWatchedPrices);
    }

    public StockBatchJobRunResponse executePendingOrders() {
        OrderExecutionMode normalizedMode = OrderExecutionMode.fromProperty(executionMode);
        if (normalizedMode == OrderExecutionMode.INTERNAL_ORDER_BOOK) {
            return run("order-execution", normalizedMode.propertyValue(), internalOrderBookExecutionService::executeEligibleOrders);
        }
        return run("order-execution", normalizedMode.propertyValue(), orderExecutionService::executeEligibleOrders);
    }

    public StockBatchJobRunResponse settlePortfolios() {
        return run("portfolio-settlement", NOT_APPLICABLE, portfolioSettlementService::settleToday);
    }

    private StockBatchJobRunResponse run(String job, String mode, IntSupplier action) {
        LocalDateTime startedAt = LocalDateTime.now();
        ReentrantLock lock = jobLocks.computeIfAbsent(job, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return new StockBatchJobRunResponse(job, SKIPPED, mode, 0, "Job is already running", startedAt, LocalDateTime.now());
        }
        try {
            int processedCount = action.getAsInt();
            return new StockBatchJobRunResponse(job, COMPLETED, mode, processedCount, "Job completed", startedAt, LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("Stock batch job failed: job={}, mode={}, reason={}", job, mode, ex.getMessage(), ex);
            return new StockBatchJobRunResponse(job, FAILED, mode, 0, ex.getMessage(), startedAt, LocalDateTime.now());
        } finally {
            lock.unlock();
        }
    }
}
