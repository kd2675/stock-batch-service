package stock.batch.service.common.vo;

public record AutoParticipantCashFlowControlRequest(
        Boolean runtimeEnabled,
        String updatedBy
) {
}
