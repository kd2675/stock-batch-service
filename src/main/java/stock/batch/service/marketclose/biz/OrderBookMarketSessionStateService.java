package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderBookMarketSessionStateService {

    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final PostCloseCycleService postCloseCycleService;

    public int syncCurrentSession() {
        LocalDateTime now = simulationMarketSessionService.currentSimulationDateTime();
        SimulationMarketSession session = simulationMarketSessionService.sessionAt(now);
        return switch (session) {
            case REGULAR -> synchronizeRegularSession(now);
            case PRE_OPEN -> synchronizePreOpenSession(now);
            case AFTER_CLOSE -> synchronizeAfterCloseSession(now);
        };
    }

    private int synchronizeRegularSession(LocalDateTime now) {
        LocalDate businessDate = now.toLocalDate();
        if (marketSessionFenceService.isRegularSessionSynchronized(businessDate)) {
            return 0;
        }
        return openRegularSession(now);
    }

    private int synchronizePreOpenSession(LocalDateTime now) {
        LocalDate rawSimulationDate = now.toLocalDate();
        if (marketSessionFenceService.isPreOpenSynchronized(rawSimulationDate)) {
            return 0;
        }
        return marketSessionFenceService.keepClosedForPreOpen(rawSimulationDate, now);
    }

    private int synchronizeAfterCloseSession(LocalDateTime now) {
        LocalDate rawSimulationDate = now.toLocalDate();
        MarketSessionFenceService.MarketBusinessStateSnapshot state = marketSessionFenceService.businessState();
        LocalDate businessDate = state == null ? rawSimulationDate : state.activeBusinessDate();
        if (marketSessionFenceService.isAfterCloseSynchronized(businessDate, rawSimulationDate)) {
            return 0;
        }
        /*
         * Publish CLOSE_REQUESTED before waiting for the per-symbol fence drain. Low-frequency
         * cash/account mutations use the full-market cycle as their admission guard: a mutation
         * that already owns the shared business-state lock completes before beginClose(), while a
         * later mutation observes this cycle and fails before taking account locks. Creating the
         * cycle after beginClose() would leave a short unguarded mutation window.
         */
        postCloseCycleService.ensureFullMarketCycle(businessDate, LocalDateTime.now());
        return marketSessionFenceService.beginClose(businessDate, rawSimulationDate, now, null);
    }

    /**
     * Invariant: a new regular session must not open until the previous business date is fully
     * post-processed. Closing the market again is intentional because order/execution jobs key off
     * this market status.
     */
    private int openRegularSession(LocalDateTime now) {
        LocalDate businessDate = now.toLocalDate();
        LocalDate missingCloseDate = missingPreviousCloseDate(businessDate);
        if (missingCloseDate != null) {
            int closedMarkets = marketSessionFenceService.keepClosedForPreOpen(
                    businessDate,
                    now
            );
            log.warn(
                    "Order-book regular session open blocked because previous post-close processing is incomplete: businessDate={}, closedMarkets={}",
                    missingCloseDate,
                    closedMarkets
            );
            return closedMarkets;
        }
        return marketSessionFenceService.openRegularSession(
                businessDate,
                now
        );
    }

    private LocalDate missingPreviousCloseDate(LocalDate currentDate) {
        LocalDate baseDate = simulationMarketSessionService.baseSimulationDate();
        MarketSessionFenceService.MarketBusinessStateSnapshot state = marketSessionFenceService.businessState();
        if (state == null) {
            LocalDate previousDate = currentDate.minusDays(1);
            if (previousDate.isBefore(baseDate)) {
                return null;
            }
            return postCloseCycleService.isReadyToOpen(previousDate) ? null : previousDate;
        }
        LocalDate activeBusinessDate = state.activeBusinessDate();
        if (activeBusinessDate.equals(currentDate)) {
            return null;
        }
        if (!activeBusinessDate.plusDays(1).equals(currentDate)) {
            return activeBusinessDate;
        }
        return postCloseCycleService.isReadyToOpen(activeBusinessDate) ? null : activeBusinessDate;
    }
}
