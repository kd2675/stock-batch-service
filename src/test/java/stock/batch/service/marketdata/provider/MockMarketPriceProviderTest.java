package stock.batch.service.marketdata.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.simulation.SimulationClockService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockMarketPriceProviderTest {

    @Test
    void fetch_usesSimulationClockForQuoteTimeAndPriceMovementSeed() {
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        LocalDateTime firstSimulationTime = LocalDateTime.of(2026, 7, 1, 10, 5);
        LocalDateTime secondSimulationTime = LocalDateTime.of(2026, 7, 1, 10, 37);
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(firstSimulationTime)
                .thenReturn(secondSimulationTime);
        MockMarketPriceProvider provider = new MockMarketPriceProvider(simulationClockService);
        BigDecimal previousPrice = new BigDecimal("70000.00");

        MarketPriceQuote firstQuote = provider.fetch("005930", previousPrice);
        MarketPriceQuote secondQuote = provider.fetch("005930", previousPrice);

        assertThat(firstQuote.priceTime()).isEqualTo(firstSimulationTime);
        assertThat(firstQuote.currentPrice()).isEqualByComparingTo(expectedPrice("005930", previousPrice, firstSimulationTime));
        assertThat(secondQuote.priceTime()).isEqualTo(secondSimulationTime);
        assertThat(secondQuote.currentPrice()).isEqualByComparingTo(expectedPrice("005930", previousPrice, secondSimulationTime));
    }

    private BigDecimal expectedPrice(String symbol, BigDecimal previousPrice, LocalDateTime simulationTime) {
        int hash = Math.abs((symbol + simulationTime.getMinute()).hashCode());
        BigDecimal basisPoint = BigDecimal.valueOf((hash % 41) - 20)
                .divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);
        return previousPrice.multiply(BigDecimal.ONE.add(basisPoint))
                .setScale(2, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE);
    }
}
