package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.InstitutionShadowDecisionService;
import stock.batch.service.batch.common.support.LightweightBatchTask;

@Component
@RequiredArgsConstructor
public class InstitutionShadowDecisionJob implements LightweightBatchTask {

    public static final String JOB_NAME = "institution-shadow-decision";
    private static final String EXECUTION_MODE = "portfolio-shadow";

    private final InstitutionShadowDecisionService decisionService;

    @Override
    public String taskName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public int run() {
        return decisionService.runShadowStep();
    }
}
