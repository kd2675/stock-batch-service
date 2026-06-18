package stock.batch.service.common.act;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.batch.service.common.vo.StockBatchServiceStatus;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/internal/stock-batch/v1/system")
public class StockBatchSystemController {

    @GetMapping("/status")
    public ResponseDataDTO<StockBatchServiceStatus> status() {
        return ResponseDataDTO.of(
                new StockBatchServiceStatus(
                        "stock-batch-service",
                        List.of("market-data", "execution", "settlement", "scheduler"),
                        true
                )
        );
    }

}
