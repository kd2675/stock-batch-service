package stock.batch.service.batch.corporateaction.model;

public record ShareEntitlementRow(
        long id,
        long accountId,
        String symbol,
        long shareQuantity
) {
}
