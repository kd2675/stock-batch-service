package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoMarketDailyRegimePreCreateServiceTest {

    @Test
    void preCreateDailyRegimes_validRequest_activatesRolesBeforeFenceAndRegime() {
        LocalDate businessDate = LocalDate.of(2026, 7, 29);
        LocalDateTime preparedAt = LocalDateTime.of(2026, 7, 29, 5, 0);
        SimulationClockService clockService = mock(SimulationClockService.class);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketDailyRegimeService dailyRegimeService =
                mock(AutoMarketDailyRegimeService.class);
        MarketRoleScheduledActivationService roleActivationService =
                mock(MarketRoleScheduledActivationService.class);
        OrderBookMarketSessionStateService marketSessionStateService =
                mock(OrderBookMarketSessionStateService.class);
        when(autoMarketReader.findDailyRegimePreparationConfigs())
                .thenReturn(List.of());
        when(dailyRegimeService.ensureFullDayDailyRegimes(
                List.of(),
                businessDate,
                preparedAt
        )).thenReturn(4);
        AutoMarketDailyRegimePreCreateService service =
                new AutoMarketDailyRegimePreCreateService(
                        clockService,
                        marketSessionService,
                        autoMarketReader,
                        dailyRegimeService,
                        roleActivationService,
                        marketSessionStateService
                );

        int createdCount = service.preCreateDailyRegimes(
                businessDate,
                preparedAt
        );

        InOrder preparationOrder = inOrder(
                roleActivationService,
                marketSessionStateService,
                autoMarketReader,
                dailyRegimeService
        );
        preparationOrder.verify(roleActivationService)
                .activateForPreOpen(businessDate, preparedAt);
        preparationOrder.verify(marketSessionStateService)
                .syncPreOpen(businessDate, preparedAt);
        preparationOrder.verify(autoMarketReader)
                .findDailyRegimePreparationConfigs();
        preparationOrder.verify(dailyRegimeService)
                .ensureFullDayDailyRegimes(List.of(), businessDate, preparedAt);
        assertThat(createdCount).isEqualTo(4);
    }
}
