package stock.batch.service.common.act;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.batch.service.common.biz.StockBatchJobService;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/stock-batch/v1/jobs")
public class StockBatchJobController {

    private final StockBatchJobService stockBatchJobService;

    @PostMapping("/market-data/refresh")
    public ResponseDataDTO<StockBatchJobRunResponse> refreshMarketData() {
        return ResponseDataDTO.of(stockBatchJobService.refreshMarketData());
    }

    @PostMapping("/order-execution/run")
    public ResponseDataDTO<StockBatchJobRunResponse> executePendingOrders() {
        return ResponseDataDTO.of(stockBatchJobService.executePendingOrders());
    }

    @PostMapping("/portfolio-settlement/run")
    public ResponseDataDTO<StockBatchJobRunResponse> settlePortfolios() {
        return ResponseDataDTO.of(stockBatchJobService.settlePortfolios());
    }
}
