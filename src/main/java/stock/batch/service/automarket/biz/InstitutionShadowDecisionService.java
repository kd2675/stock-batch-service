package stock.batch.service.automarket.biz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketHistoricalSignal;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
@Slf4j
public class InstitutionShadowDecisionService {

    private final InstitutionShadowPortfolioRepository repository;
    private final InstitutionShadowPortfolioProcessor processor;
    private final InstitutionOrderIntentExecutionService intentExecutionService;
    private final InstitutionShadowPortfolioRunMetrics metrics;
    private final AutoMarketReader autoMarketReader;
    private final AutoMarketDailyRegimeService dailyRegimeService;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService marketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final int portfolioLimitPerRun;

    InstitutionShadowDecisionService(
            InstitutionShadowPortfolioRepository repository,
            InstitutionShadowPortfolioProcessor processor,
            InstitutionOrderIntentExecutionService intentExecutionService,
            InstitutionShadowPortfolioRunMetrics metrics,
            AutoMarketReader autoMarketReader,
            AutoMarketDailyRegimeService dailyRegimeService,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService marketSessionService,
            MarketSessionFenceService marketSessionFenceService,
            @Value("${stock.batch.institution-shadow.portfolio-limit-per-run:20}") int portfolioLimitPerRun
    ) {
        this.repository = repository;
        this.processor = processor;
        this.intentExecutionService = intentExecutionService;
        this.metrics = metrics;
        this.autoMarketReader = autoMarketReader;
        this.dailyRegimeService = dailyRegimeService;
        this.simulationClockService = simulationClockService;
        this.marketSessionService = marketSessionService;
        this.marketSessionFenceService = marketSessionFenceService;
        this.portfolioLimitPerRun = Math.clamp(portfolioLimitPerRun, 1, 100);
    }

    public int runShadowStep() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (!clock.running()
                || marketSessionService.sessionAt(clock.simulationDateTime()) != SimulationMarketSession.REGULAR
                || !marketSessionFenceService.hasOpenOrderBookMarket()) {
            return 0;
        }
        List<Long> duePortfolioIds = repository.findDuePortfolioIds(
                clock.simulationDateTime(),
                portfolioLimitPerRun
        );
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            intentExecutionService.runPendingIntents(
                    Map.of(),
                    clock.simulationDate(),
                    clock.simulationDateTime()
            );
            return 0;
        }
        configs = dailyRegimeService.applyDailyRegimes(
                configs,
                clock.simulationDate(),
                clock.simulationDateTime()
        );
        List<String> symbols = configs.stream().map(AutoMarketConfig::symbol).distinct().toList();
        Map<String, AutoMarketHistoricalSignal> historicalSignals =
                autoMarketReader.findHistoricalMarketSignals(symbols, clock.simulationDate());
        Map<String, InstitutionMarketInput> marketInputs = configs.stream()
                .collect(Collectors.toMap(
                        AutoMarketConfig::symbol,
                        config -> InstitutionMarketInput.from(
                                config.symbol(),
                                config.currentPrice(),
                                config.primaryPressure(),
                                config.secondaryPressure(),
                                historicalSignals.get(config.symbol()),
                                config.reportPricePressure()
                        ),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, AutoMarketConfig> configsBySymbol = configs.stream()
                .collect(Collectors.toMap(
                        AutoMarketConfig::symbol,
                        config -> config,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        int completed = 0;
        for (Long portfolioId : duePortfolioIds) {
            try {
                InstitutionShadowPortfolioProcessor.ProcessResult result =
                        processor.process(portfolioId, clock.simulationDateTime(), marketInputs);
                metrics.record(result);
                if (result == InstitutionShadowPortfolioProcessor.ProcessResult.COMPLETED) {
                    completed++;
                }
            } catch (RuntimeException ex) {
                metrics.recordUnexpectedFailure();
                log.warn(
                        "Institution shadow transaction rolled back: portfolioId={}, reason={}",
                        portfolioId,
                        ex.getMessage(),
                        ex
                );
            }
        }
        intentExecutionService.runPendingIntents(
                Map.copyOf(configsBySymbol),
                clock.simulationDate(),
                clock.simulationDateTime()
        );
        return completed;
    }
}
