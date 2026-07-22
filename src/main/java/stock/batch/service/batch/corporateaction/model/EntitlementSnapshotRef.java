package stock.batch.service.batch.corporateaction.model;

public record EntitlementSnapshotRef(
        long closeCycleId,
        long closeRunId
) {
}
