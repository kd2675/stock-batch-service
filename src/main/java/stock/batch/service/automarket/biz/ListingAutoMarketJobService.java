package stock.batch.service.automarket.biz;

import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingAutoMarketJobService {

    private final AutoMarketReader autoMarketReader;
    private final ListingAutoAccountOrderService listingAutoAccountOrderService;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;

    public int runListingAutoMarket() {
        long startedNanos = System.nanoTime();
        if (!isRegularSessionActive()) {
            return 0;
        }
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (AutoMarketConfig config : configs) {
            processed += runIntInTransaction(() -> listingAutoAccountOrderService.run(config));
        }
        log.info(
                "Listing auto market completed: symbols={}, processedCount={}, elapsedMs={}",
                configs.size(),
                processed,
                elapsedMillis(startedNanos)
        );
        return processed;
    }

    private boolean isRegularSessionActive() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        return !clock.running() || simulationMarketSessionService.isRegularSession();
    }

    private int runIntInTransaction(Supplier<Integer> action) {
        Integer result = transactionTemplate.execute(status -> action.get());
        return result == null ? 0 : result;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
