package stock.batch.service.batch.signal.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.simulation.SimulationClockService;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchJobSignalProcessor {

    private static final String SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN = "AUTO_PARTICIPANT_CASH_FLOW_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_RUN = "MARKET_CLOSE_ROLLOVER_RUN";
    private static final String SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL = "MARKET_CLOSE_ROLLOVER_SYMBOL";
    private static final String SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL = "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL";
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final int MAX_SIGNAL_CHUNK_LIMIT = 100;

    private final BatchJobSignalReader signalReader;
    private final BatchJobSignalWriter signalWriter;
    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final SimulationClockService simulationClockService;
    private final BatchJobSignalValidationService signalValidationService;
    private final BatchJobSignalLeaseHeartbeat signalLeaseHeartbeat;

    public int processPendingSignals(int limit) {
        int processedCount = 0;
        int boundedLimit = validateChunkLimit(limit);
        var simulationNow = simulationClockService.currentMarketDateTime();
        if (!signalReader.hasClaimable(simulationNow)) {
            return 0;
        }
        for (int index = 0; index < boundedLimit; index++) {
            var signal = signalReader.claimNext(simulationNow);
            if (signal.isEmpty()) {
                break;
            }
            if (process(signal.get())) {
                processedCount++;
            }
        }
        return processedCount;
    }

    public int deadLetterExhaustedSignals() {
        return signalReader.deadLetterExhaustedLeases(java.time.LocalDateTime.now());
    }

    public static int validateChunkLimit(int limit) {
        if (limit < 1 || limit > MAX_SIGNAL_CHUNK_LIMIT) {
            throw new IllegalStateException(
                    "stock.batch.signal.chunk-limit must be between 1 and %d: %d"
                            .formatted(MAX_SIGNAL_CHUNK_LIMIT, limit)
            );
        }
        return limit;
    }

    private boolean process(BatchJobSignal signal) {
        try {
            signalValidationService.validate(signal);
            StockBatchJobRunResponse response = signalLeaseHeartbeat.execute(signal, () -> runSignal(signal));
            if (StockBatchJobRunResponses.isManualCashFlowAutoEnabledSkip(response)) {
                signalWriter.complete(signal, response);
                log.info(
                        "Stock batch signal completed without manual cash flow because automatic cash flow is enabled: id={}, type={}, job={}, mode={}",
                        signal.id(),
                        signal.signalType(),
                        signal.jobName(),
                        signal.executionMode()
                );
                return true;
            }
            if (StockBatchJobRunResponses.isAlreadyCompleteSkip(response)) {
                signalWriter.complete(signal, response);
                log.info(
                        "Stock batch signal completed because its job instance was already complete: id={}, type={}, job={}, mode={}",
                        signal.id(),
                        signal.signalType(),
                        signal.jobName(),
                        signal.executionMode()
                );
                return true;
            }
            if (isSkipped(response)) {
                signalWriter.defer(signal, response);
                log.warn(
                        "Stock batch signal deferred because target job was skipped: id={}, type={}, job={}, mode={}, reason={}",
                        signal.id(),
                        signal.signalType(),
                        signal.jobName(),
                        signal.executionMode(),
                        response.message()
                );
                return false;
            }
            if (StockBatchJobRunResponses.isFailed(response)) {
                signalWriter.retry(signal, new IllegalStateException(response.message()));
                return false;
            }
            signalWriter.complete(signal, response);
            return true;
        } catch (BatchJobSignalClaimLostException ex) {
            logClaimLost(signal, ex);
            return false;
        } catch (RuntimeException ex) {
            try {
                if (ex instanceof IllegalArgumentException) {
                    signalWriter.fail(signal, ex);
                } else {
                    signalWriter.retry(signal, ex);
                }
            } catch (BatchJobSignalClaimLostException claimLost) {
                logClaimLost(signal, claimLost);
                return false;
            }
            log.warn(
                    "Stock batch signal failed: id={}, type={}, job={}, mode={}, reason={}",
                    signal.id(),
                    signal.signalType(),
                    signal.jobName(),
                    signal.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return true;
        }
    }

    private void logClaimLost(BatchJobSignal signal, RuntimeException failure) {
        log.warn(
                "Stock batch signal result was not written because claim ownership was lost: "
                        + "id={}, type={}, job={}, mode={}, reason={}",
                signal.id(),
                signal.signalType(),
                signal.jobName(),
                signal.executionMode(),
                failure.getMessage()
        );
    }

    private boolean isSkipped(StockBatchJobRunResponse response) {
        return response != null && STATUS_SKIPPED.equals(response.status());
    }

    private StockBatchJobRunResponse runSignal(BatchJobSignal signal) {
        return switch (signal.signalType()) {
            case SIGNAL_AUTO_PARTICIPANT_CASH_FLOW_RUN ->
                    stockBatchJobLauncher.fundAutoParticipantsManually(
                            signal.requestedBusinessDate(),
                            signal.expectedCycleId(),
                            signal.id()
                    );
            case SIGNAL_MARKET_CLOSE_ROLLOVER_RUN -> stockBatchJobLauncher.rolloverClosingPrices(
                    signal.requestedBusinessDate(),
                    signal.expectedCycleId(),
                    signal.requestedSessionEpoch(),
                    signal.id()
            );
            case SIGNAL_MARKET_CLOSE_ROLLOVER_SYMBOL ->
                    stockBatchJobLauncher.rolloverClosingPrices(
                            requireSymbol(signal),
                            signal.requestedBusinessDate(),
                            signal.expectedCycleId(),
                            signal.requestedSessionEpoch(),
                            signal.id()
                    );
            case SIGNAL_ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL ->
                    stockBatchJobLauncher.cancelOpenOrderBookOrders(
                            requireSymbol(signal),
                            signal.requestedBusinessDate(),
                            signal.requestedSessionEpoch(),
                            signal.id()
                    );
            default -> throw new IllegalArgumentException("Unknown batch job signal type: " + signal.signalType());
        };
    }

    private String requireSymbol(BatchJobSignal signal) {
        if (!StringUtils.hasText(signal.symbol())) {
            throw new IllegalArgumentException("Signal requires symbol: id=" + signal.id());
        }
        return signal.symbol().trim().toUpperCase();
    }
}
