package stock.batch.service.common.vo;

public record BatchJobRuntimeControlRequest(
        Boolean runtimeEnabled,
        String updatedBy
) {
}
