package stock.batch.service.common.vo;

import java.util.List;

public record StockBatchServiceStatus(
        String serviceName,
        List<String> plannedJobs,
        boolean redisExpected
) {
}
