package stock.batch.service.batch.automarket.reader;

record ParticipantSymbolStrategyConfig(
        String userKey,
        String symbol,
        boolean enabled,
        int intensity
) {
}
