package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkippedBusinessDateRecoveryService {

    private static final String SKIP_REASON = "Raw simulation time passed before this business date could open";

    private final MarketSessionFenceService marketSessionFenceService;
    private final PostCloseCycleService postCloseCycleService;

    public boolean shouldSkip(LocalDate businessDate, LocalDateTime simulationNow, java.time.LocalTime closeTime) {
        if (businessDate == null || simulationNow == null || closeTime == null) {
            return false;
        }
        LocalDate rawDate = simulationNow.toLocalDate();
        return rawDate.isAfter(businessDate)
                || (rawDate.equals(businessDate) && !simulationNow.toLocalTime().isBefore(closeTime));
    }

    @Transactional
    public void skipPreparedBusinessDate(
            LocalDate expectedActiveBusinessDate,
            LocalDate skippedBusinessDate,
            LocalDateTime simulationNow,
            java.time.LocalTime closeTime
    ) {
        skipSequentialBusinessDate(
                expectedActiveBusinessDate,
                skippedBusinessDate,
                simulationNow,
                closeTime,
                SKIP_REASON
        );
    }

    @Transactional
    public Optional<LocalDate> recoverNextMissedBusinessDate(
            LocalDateTime simulationNow,
            java.time.LocalTime closeTime
    ) {
        MarketSessionFenceService.MarketBusinessStateSnapshot state = marketSessionFenceService.businessState();
        if (state == null || !postCloseCycleService.isCompleted(state.activeBusinessDate())) {
            return Optional.empty();
        }
        LocalDate nextBusinessDate = state.activeBusinessDate().plusDays(1);
        if (!shouldSkip(nextBusinessDate, simulationNow, closeTime)) {
            return Optional.empty();
        }
        skipSequentialBusinessDate(
                state.activeBusinessDate(),
                nextBusinessDate,
                simulationNow,
                closeTime,
                SKIP_REASON
        );
        return Optional.of(nextBusinessDate);
    }

    private void skipSequentialBusinessDate(
            LocalDate expectedActiveBusinessDate,
            LocalDate skippedBusinessDate,
            LocalDateTime simulationNow,
            java.time.LocalTime closeTime,
            String reason
    ) {
        if (closeTime == null) {
            throw new IllegalArgumentException("closeTime is required");
        }
        marketSessionFenceService.advanceClosedBusinessDate(
                expectedActiveBusinessDate,
                skippedBusinessDate,
                simulationNow.toLocalDate(),
                simulationNow
        );
        postCloseCycleService.ensureSkippedFullMarketCycle(
                skippedBusinessDate,
                reason,
                simulationNow
        );
        /*
         * Do not run an empty close/settlement pipeline for every missed day. When the raw
         * clock has finally caught up, however, the last skipped date must carry the bounded
         * PRE_OPEN pipeline for the next date. Otherwise a completed SKIPPED cycle would satisfy
         * the open guard without corporate transforms, market-data preparation, auto-market
         * queue recovery, or readiness validation. This update touches only the small cycle
         * control row; order and execution ledgers are never read during missed-date recovery.
         */
        LocalDate nextBusinessDate = skippedBusinessDate.plusDays(1);
        if (!shouldSkip(nextBusinessDate, simulationNow, closeTime)) {
            postCloseCycleService.prepareSkippedCycleForNextOpen(skippedBusinessDate, simulationNow);
        }
    }
}
