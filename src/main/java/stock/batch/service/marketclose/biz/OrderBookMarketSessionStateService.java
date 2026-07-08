package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderBookMarketSessionStateService {

    private final JdbcClient jdbcClient;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketClosePostProcessingCompletionService postProcessingCompletionService;

    @Transactional
    public int syncCurrentSession() {
        SimulationMarketSession session = simulationMarketSessionService.currentSession();
        LocalDateTime now = simulationMarketSessionService.currentSimulationDateTime();
        return switch (session) {
            case REGULAR -> openRegularSession(now, currentOpenBoundary());
            case PRE_OPEN, AFTER_CLOSE -> closeRegularSession(now);
        };
    }

    private LocalDateTime currentOpenBoundary() {
        return simulationMarketSessionService.currentSimulationDate()
                .atTime(simulationMarketSessionService.openTime());
    }

    /**
     * Invariant: a new regular session must not open until the previous business date is fully
     * post-processed. Closing the market again is intentional because order/execution jobs key off
     * this market status.
     */
    private int openRegularSession(LocalDateTime now, LocalDateTime openBoundary) {
        LocalDate missingCloseDate = missingPreviousCloseDate();
        if (missingCloseDate != null) {
            int closedMarkets = closeRegularSession(now);
            log.warn(
                    "Order-book regular session open blocked because previous post-close processing is incomplete: businessDate={}, closedMarkets={}",
                    missingCloseDate,
                    closedMarkets
            );
            return closedMarkets;
        }
        return jdbcClient.sql(
                        """
                        update stock_order_book_market_config
                           set market_status = 'OPEN',
                               updated_at = ?
                         where enabled = true
                           and market_status in ('CLOSED', 'CIRCUIT_BREAKER')
                           and updated_at < ?
                        """
                )
                .param(now)
                .param(openBoundary)
                .update();
    }

    private LocalDate missingPreviousCloseDate() {
        LocalDate currentDate = simulationMarketSessionService.currentSimulationDate();
        LocalDate baseDate = simulationMarketSessionService.baseSimulationDate();
        LocalDate previousDate = currentDate.minusDays(1);
        if (previousDate.isBefore(baseDate)) {
            return null;
        }
        return postProcessingCompletionService.isComplete(previousDate) ? null : previousDate;
    }

    private int closeRegularSession(LocalDateTime now) {
        return jdbcClient.sql(
                        """
                        update stock_order_book_market_config
                           set market_status = 'CLOSED',
                               updated_at = ?
                         where enabled = true
                           and market_status = 'OPEN'
                        """
                )
                .param(now)
                .update();
    }
}
