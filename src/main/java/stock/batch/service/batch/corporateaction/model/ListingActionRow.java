package stock.batch.service.batch.corporateaction.model;

public record ListingActionRow(
        long id,
        String symbol,
        long shareQuantity
) {
}
