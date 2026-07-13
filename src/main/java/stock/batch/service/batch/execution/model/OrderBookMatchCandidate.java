package stock.batch.service.batch.execution.model;

public record OrderBookMatchCandidate(
        long buyOrderId,
        long buyAccountId,
        long sellOrderId,
        long sellAccountId
) {
}
