package stock.batch.service.automarket.biz;

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
public class MarketRoleScheduledActivationService {

    private final LiquidityProviderScheduledPolicyActivationService liquidityProviderActivationService;
    private final IssueUnderwriterScheduledSupplyActivationService underwriterActivationService;
    private final SimulationMarketSessionService marketSessionService;

    public ActivationResult activateForPreOpen(
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
        if (marketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            return new ActivationResult(0, 0);
        }
        int liquidityProviderCount = activateLiquidityProviders(businessDate, activatedAt);
        int underwriterCount = activateUnderwriters(businessDate, activatedAt);
        return new ActivationResult(liquidityProviderCount, underwriterCount);
    }

    private int activateLiquidityProviders(
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
        try {
            return liquidityProviderActivationService.activateDuePoliciesForPreOpen(
                    businessDate,
                    activatedAt
            );
        } catch (RuntimeException ex) {
            log.error(
                    "Scheduled liquidity-provider activation failed without blocking existing markets: businessDate={}",
                    businessDate,
                    ex
            );
            return 0;
        }
    }

    private int activateUnderwriters(
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
        try {
            return underwriterActivationService.activateDuePolicies(
                    businessDate,
                    activatedAt
            );
        } catch (RuntimeException ex) {
            log.error(
                    "Scheduled issue-underwriter activation failed without blocking existing markets: businessDate={}",
                    businessDate,
                    ex
            );
            return 0;
        }
    }

    public record ActivationResult(
            int liquidityProviderCount,
            int underwriterCount
    ) {
        public int totalCount() {
            return Math.addExact(liquidityProviderCount, underwriterCount);
        }
    }
}
