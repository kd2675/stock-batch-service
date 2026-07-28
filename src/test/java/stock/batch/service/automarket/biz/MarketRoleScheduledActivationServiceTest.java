package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketRoleScheduledActivationServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 29);
    private static final LocalDateTime ACTIVATED_AT =
            LocalDateTime.of(2026, 7, 29, 5, 0);

    @Test
    void activateForPreOpen_dueRoles_activatesLiquidityProviderBeforeUnderwriter() {
        LiquidityProviderScheduledPolicyActivationService liquidityProviderService =
                mock(LiquidityProviderScheduledPolicyActivationService.class);
        IssueUnderwriterScheduledSupplyActivationService underwriterService =
                mock(IssueUnderwriterScheduledSupplyActivationService.class);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        when(liquidityProviderService.activateDuePoliciesForPreOpen(
                BUSINESS_DATE,
                ACTIVATED_AT
        )).thenReturn(2);
        when(underwriterService.activateDuePolicies(
                BUSINESS_DATE,
                ACTIVATED_AT
        )).thenReturn(1);
        MarketRoleScheduledActivationService service =
                new MarketRoleScheduledActivationService(
                        liquidityProviderService,
                        underwriterService,
                        marketSessionService
                );

        MarketRoleScheduledActivationService.ActivationResult result =
                service.activateForPreOpen(BUSINESS_DATE, ACTIVATED_AT);

        InOrder activationOrder = inOrder(
                liquidityProviderService,
                underwriterService
        );
        activationOrder.verify(liquidityProviderService)
                .activateDuePoliciesForPreOpen(BUSINESS_DATE, ACTIVATED_AT);
        activationOrder.verify(underwriterService)
                .activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT);
        assertThat(result.totalCount()).isEqualTo(3);
    }

    @Test
    void activateForPreOpen_liquidityProviderFailure_continuesUnderwriterActivation() {
        LiquidityProviderScheduledPolicyActivationService liquidityProviderService =
                mock(LiquidityProviderScheduledPolicyActivationService.class);
        IssueUnderwriterScheduledSupplyActivationService underwriterService =
                mock(IssueUnderwriterScheduledSupplyActivationService.class);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.PRE_OPEN);
        when(liquidityProviderService.activateDuePoliciesForPreOpen(
                BUSINESS_DATE,
                ACTIVATED_AT
        )).thenThrow(new IllegalStateException("LP activation failed"));
        when(underwriterService.activateDuePolicies(
                BUSINESS_DATE,
                ACTIVATED_AT
        )).thenReturn(1);
        MarketRoleScheduledActivationService service =
                new MarketRoleScheduledActivationService(
                        liquidityProviderService,
                        underwriterService,
                        marketSessionService
                );

        MarketRoleScheduledActivationService.ActivationResult result =
                service.activateForPreOpen(BUSINESS_DATE, ACTIVATED_AT);

        assertThat(result.liquidityProviderCount()).isZero();
        assertThat(result.underwriterCount()).isEqualTo(1);
    }

    @Test
    void activateForPreOpen_regularSession_doesNotActivateOverdueRoles() {
        LiquidityProviderScheduledPolicyActivationService liquidityProviderService =
                mock(LiquidityProviderScheduledPolicyActivationService.class);
        IssueUnderwriterScheduledSupplyActivationService underwriterService =
                mock(IssueUnderwriterScheduledSupplyActivationService.class);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        when(marketSessionService.currentSession())
                .thenReturn(SimulationMarketSession.REGULAR);
        MarketRoleScheduledActivationService service =
                new MarketRoleScheduledActivationService(
                        liquidityProviderService,
                        underwriterService,
                        marketSessionService
                );

        MarketRoleScheduledActivationService.ActivationResult result =
                service.activateForPreOpen(BUSINESS_DATE, ACTIVATED_AT);

        verifyNoInteractions(liquidityProviderService, underwriterService);
        assertThat(result.totalCount()).isZero();
    }
}
