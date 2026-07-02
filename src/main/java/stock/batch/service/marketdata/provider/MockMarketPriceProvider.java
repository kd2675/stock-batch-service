package stock.batch.service.marketdata.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import stock.batch.service.simulation.SimulationClockService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.market-data", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockMarketPriceProvider implements MarketPriceProvider {

    private static final String PROVIDER_NAME = "mock-provider";

    private final SimulationClockService simulationClockService;

    @Override
    public MarketPriceQuote fetch(String symbol, BigDecimal previousPrice) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        int hash = Math.abs((symbol + now.getMinute()).hashCode());
        BigDecimal basisPoint = BigDecimal.valueOf((hash % 41) - 20)
                .divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);
        BigDecimal changed = previousPrice.multiply(BigDecimal.ONE.add(basisPoint));
        BigDecimal currentPrice = changed.setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ONE);
        return new MarketPriceQuote(symbol, currentPrice, PROVIDER_NAME, now);
    }
}
