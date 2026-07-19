package stock.batch.service.batch.automarket.model;

public record AutoMarketDailyRegimePreparationConfig(
        String symbol,
        AutoMarketDistributionBias primaryDistributionBias,
        AutoMarketRegimeCountWeights countWeights
) {
    public AutoMarketDailyRegimePreparationConfig {
        primaryDistributionBias = primaryDistributionBias == null
                ? AutoMarketDistributionBias.NEUTRAL
                : primaryDistributionBias;
        countWeights = countWeights == null ? AutoMarketRegimeCountWeights.ALWAYS_FOUR : countWeights;
    }
}
