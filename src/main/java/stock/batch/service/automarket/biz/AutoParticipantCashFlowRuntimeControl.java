package stock.batch.service.automarket.biz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.policy.BatchJobRuntimeStatus;
import stock.batch.service.common.vo.AutoParticipantCashFlowStatusResponse;

@Component
public class AutoParticipantCashFlowRuntimeControl {

    private final BatchJobRuntimeControl batchJobRuntimeControl;
    private final boolean schedulerConfigured;

    public AutoParticipantCashFlowRuntimeControl(
            BatchJobRuntimeControl batchJobRuntimeControl,
            @Value("${stock.batch.auto-participant-cash-flow.enabled:true}") boolean schedulerConfigured
    ) {
        this.batchJobRuntimeControl = batchJobRuntimeControl;
        this.schedulerConfigured = schedulerConfigured;
    }

    public boolean shouldRunScheduledJob() {
        return status().effectiveEnabled();
    }

    public AutoParticipantCashFlowStatusResponse status() {
        return toResponse(batchJobRuntimeControl.status(
                AutoParticipantCashFlowJob.JOB_NAME,
                schedulerConfigured
        ));
    }

    public AutoParticipantCashFlowStatusResponse update(boolean nextRuntimeEnabled, String updatedBy) {
        return toResponse(batchJobRuntimeControl.update(
                AutoParticipantCashFlowJob.JOB_NAME,
                schedulerConfigured,
                nextRuntimeEnabled,
                updatedBy
        ));
    }

    private AutoParticipantCashFlowStatusResponse toResponse(BatchJobRuntimeStatus status) {
        return new AutoParticipantCashFlowStatusResponse(
                status.schedulerConfigured(),
                status.runtimeEnabled(),
                status.effectiveEnabled(),
                status.updatedBy(),
                status.updatedAt()
        );
    }
}
