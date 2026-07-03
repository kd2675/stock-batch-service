package stock.batch.service.automarket.biz;

record AutoParticipantOrderGenerationResult(
        int generatedOrderCount,
        int reservedBuyCount,
        int reservedSellCount,
        int failedReserveCount
) {
}
