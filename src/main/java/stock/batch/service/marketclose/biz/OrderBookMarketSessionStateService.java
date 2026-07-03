package stock.batch.service.marketclose.biz;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Service
@RequiredArgsConstructor
public class OrderBookMarketSessionStateService {

    private final JdbcClient jdbcClient;
    private final SimulationMarketSessionService simulationMarketSessionService;

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

    private int openRegularSession(LocalDateTime now, LocalDateTime openBoundary) {
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
