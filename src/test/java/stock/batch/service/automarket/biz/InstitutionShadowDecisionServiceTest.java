package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionShadowDecisionServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    @Test
    void runShadowStep_noActiveMarketConfig_stillDrainsPendingIntents() {
        InstitutionShadowPortfolioRepository repository =
                mock(InstitutionShadowPortfolioRepository.class);
        InstitutionShadowPortfolioProcessor processor =
                mock(InstitutionShadowPortfolioProcessor.class);
        InstitutionOrderIntentExecutionService intentExecutionService =
                mock(InstitutionOrderIntentExecutionService.class);
        InstitutionShadowPortfolioRunMetrics metrics =
                mock(InstitutionShadowPortfolioRunMetrics.class);
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService dailyRegimeService =
                mock(AutoMarketDailyRegimeService.class);
        SimulationClockService simulationClockService =
                mock(SimulationClockService.class);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        MarketSessionFenceService marketSessionFenceService =
                mock(MarketSessionFenceService.class);
        when(simulationClockService.currentSnapshot()).thenReturn(clock());
        when(marketSessionService.sessionAt(NOW))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(true);
        when(repository.findDuePortfolioIds(NOW, 20)).thenReturn(List.of());
        when(autoMarketReader.findEnabledConfigs()).thenReturn(List.of());
        InstitutionShadowDecisionService service = new InstitutionShadowDecisionService(
                repository,
                processor,
                intentExecutionService,
                metrics,
                autoMarketReader,
                dailyRegimeService,
                simulationClockService,
                marketSessionService,
                marketSessionFenceService,
                20
        );

        int completed = service.runShadowStep();

        assertThat(completed).isZero();
        verify(intentExecutionService).runPendingIntents(Map.of(), TRADE_DATE, NOW);
    }

    private SimulationClockSnapshot clock() {
        return new SimulationClockSnapshot(
                TRADE_DATE,
                NOW,
                TRADE_DATE.atStartOfDay(),
                NOW,
                TRADE_DATE.atStartOfDay(),
                7_200,
                true,
                false,
                0L,
                null,
                null
        );
    }
}
