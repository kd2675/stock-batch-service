package stock.batch.service.batch.corporateaction.model;

public record StockSplitActionRow(
        long id,
        String symbol,
        int splitFrom,
        int splitTo
) {
}
