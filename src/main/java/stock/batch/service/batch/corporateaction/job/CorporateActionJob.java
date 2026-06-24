package stock.batch.service.batch.corporateaction.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.corporateaction.biz.CorporateActionService;

@Component
@RequiredArgsConstructor
public class CorporateActionJob implements StockBatchJob {

    public static final String JOB_NAME = "corporate-actions";
    private static final String EXECUTION_MODE = "order-book";

    private final CorporateActionService corporateActionService;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public int run() {
        return corporateActionService.applyDueCorporateActions();
    }
}
