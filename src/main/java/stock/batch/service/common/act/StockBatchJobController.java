package stock.batch.service.common.act;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/stock-batch/v1/jobs")
public class StockBatchJobController {

    private final StockBatchJobLauncher stockBatchJobLauncher;

    @PostMapping("/market-data/refresh")
    public ResponseDataDTO<StockBatchJobRunResponse> refreshMarketData() {
        return ResponseDataDTO.of(stockBatchJobLauncher.refreshMarketData());
    }

    @PostMapping("/virtual-price-execution/run")
    public ResponseDataDTO<StockBatchJobRunResponse> executeVirtualPriceOrders() {
        return ResponseDataDTO.of(stockBatchJobLauncher.executeVirtualPriceOrders());
    }

    @PostMapping("/order-book-execution/run")
    public ResponseDataDTO<StockBatchJobRunResponse> executeOrderBookOrders() {
        return ResponseDataDTO.of(stockBatchJobLauncher.executeOrderBookOrders());
    }

    @PostMapping("/auto-market/run")
    public ResponseDataDTO<StockBatchJobRunResponse> runAutoMarket() {
        return ResponseDataDTO.of(stockBatchJobLauncher.runAutoMarket());
    }

    @PostMapping("/portfolio-settlement/run")
    public ResponseDataDTO<StockBatchJobRunResponse> settlePortfolios() {
        return ResponseDataDTO.of(stockBatchJobLauncher.settlePortfolios());
    }

    @PostMapping("/market-close/rollover")
    public ResponseDataDTO<StockBatchJobRunResponse> rolloverClosingPrices() {
        return ResponseDataDTO.of(stockBatchJobLauncher.rolloverClosingPrices());
    }

    @PostMapping("/corporate-actions/run")
    public ResponseDataDTO<StockBatchJobRunResponse> applyCorporateActions() {
        return ResponseDataDTO.of(stockBatchJobLauncher.applyCorporateActions());
    }
}
