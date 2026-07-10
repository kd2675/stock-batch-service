package stock.batch.service.corporateaction.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.corporateaction.model.AutoParticipantCapitalIncreaseCandidate;
import stock.batch.service.batch.corporateaction.model.CapitalIncreaseSubscriptionActionRow;
import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.reader.CorporateActionReader;
import stock.batch.service.batch.corporateaction.writer.CorporateActionAccountWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionPriceWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorporateActionServiceConcurrencyTest {

    @Test
    void applyDueCorporateActions_manualSubscriptionWinsShareholderRace_skipsWithoutWithdrawingCash() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        CorporateActionReader reader = mock(CorporateActionReader.class);
        CorporateActionOrderBookOrderGuard orderGuard = mock(CorporateActionOrderBookOrderGuard.class);
        CorporateActionPriceWriter priceWriter = mock(CorporateActionPriceWriter.class);
        CorporateActionWriter writer = mock(CorporateActionWriter.class);
        CorporateActionAccountWriter accountWriter = mock(CorporateActionAccountWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
        CorporateActionTransactionExecutor transactionExecutor = mock(CorporateActionTransactionExecutor.class);
        when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> actionUnit = invocation.getArgument(0);
            return actionUnit.get();
        });
        CorporateActionService service = new CorporateActionService(
                reader,
                orderGuard,
                priceWriter,
                writer,
                accountWriter,
                clockService,
                sessionService,
                transactionExecutor
        );
        CapitalIncreaseSubscriptionActionRow action = new CapitalIncreaseSubscriptionActionRow(
                11L,
                "005930",
                "SHAREHOLDER_ALLOCATION",
                100L,
                new BigDecimal("50000.00")
        );
        AutoParticipantCapitalIncreaseCandidate candidate = new AutoParticipantCapitalIncreaseCandidate(
                21L,
                31L,
                AutoParticipantProfileType.LONG_TERM_HOLDER,
                new BigDecimal("10000000.00"),
                50L
        );
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(sessionService.currentSimulationDate()).thenReturn(today);
        when(reader.existsCompletedMarketCloseRun(today)).thenReturn(true);
        when(reader.findOpenCapitalIncreaseSubscriptions(today, "PAID_IN_CAPITAL_INCREASE", List.of("ANNOUNCED", "EX_RIGHTS_APPLIED")))
                .thenReturn(List.of(action));
        when(reader.findEventProfilePolicies()).thenReturn(List.of());
        when(reader.findCapitalIncreaseSubscriptionForUpdate(
                11L,
                today,
                "PAID_IN_CAPITAL_INCREASE",
                "EX_RIGHTS_APPLIED",
                "SHAREHOLDER_ALLOCATION"
        )).thenReturn(Optional.of(action));
        when(reader.findShareholderAllocationAutoCandidates(11L, "ANNOUNCED"))
                .thenReturn(List.of(candidate));
        when(reader.findActiveAccountCashForUpdate(31L)).thenReturn(Optional.of(new BigDecimal("10000000.00")));
        when(reader.findAnnouncedEntitlementShareQuantityForUpdate(21L, 11L, "ANNOUNCED"))
                .thenReturn(Optional.empty());

        service.applyDueCorporateActions();

        InOrder lockOrder = inOrder(reader);
        lockOrder.verify(reader).findCapitalIncreaseSubscriptionForUpdate(
                11L,
                today,
                "PAID_IN_CAPITAL_INCREASE",
                "EX_RIGHTS_APPLIED",
                "SHAREHOLDER_ALLOCATION"
        );
        lockOrder.verify(reader).findActiveAccountCashForUpdate(31L);
        lockOrder.verify(reader).findAnnouncedEntitlementShareQuantityForUpdate(21L, 11L, "ANNOUNCED");
        verify(accountWriter, never()).withdrawCashForSubscription(anyLong(), any(), any());
    }

    @Test
    void applyDueCorporateActions_eventPolicyLoadFails_continuesDividendStageAndFailsJob() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        LocalDateTime now = today.atTime(18, 30);
        CorporateActionReader reader = mock(CorporateActionReader.class);
        CorporateActionOrderBookOrderGuard orderGuard = mock(CorporateActionOrderBookOrderGuard.class);
        CorporateActionPriceWriter priceWriter = mock(CorporateActionPriceWriter.class);
        CorporateActionWriter writer = mock(CorporateActionWriter.class);
        CorporateActionAccountWriter accountWriter = mock(CorporateActionAccountWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
        CorporateActionTransactionExecutor transactionExecutor = mock(CorporateActionTransactionExecutor.class);
        when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> actionUnit = invocation.getArgument(0);
            return actionUnit.get();
        });
        CorporateActionService service = new CorporateActionService(
                reader,
                orderGuard,
                priceWriter,
                writer,
                accountWriter,
                clockService,
                sessionService,
                transactionExecutor
        );
        CapitalIncreaseSubscriptionActionRow action = new CapitalIncreaseSubscriptionActionRow(
                11L,
                "005930",
                "PUBLIC_OFFERING",
                100L,
                new BigDecimal("50000.00")
        );
        DividendEntitlementRow dividend = new DividendEntitlementRow(
                51L,
                61L,
                new BigDecimal("10000.00")
        );
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(sessionService.currentSimulationDate()).thenReturn(today);
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(reader.existsCompletedMarketCloseRun(today)).thenReturn(true);
        when(reader.findOpenCapitalIncreaseSubscriptions(
                today,
                "PAID_IN_CAPITAL_INCREASE",
                List.of("ANNOUNCED", "EX_RIGHTS_APPLIED")
        )).thenReturn(List.of(action));
        when(reader.findEventProfilePolicies()).thenThrow(new IllegalStateException("Unknown event profile"));
        when(reader.findDueCashDividendActionIds(today, "CASH_DIVIDEND", "EX_RIGHTS_APPLIED"))
                .thenReturn(List.of(41L));
        when(reader.lockDueActionForUpdate(41L, today, "CASH_DIVIDEND", "EX_RIGHTS_APPLIED", "payment_date"))
                .thenReturn(true);
        when(reader.findAnnouncedDividendEntitlements(41L, "ANNOUNCED")).thenReturn(List.of(dividend));
        when(accountWriter.creditCash(61L, new BigDecimal("10000.00"), now)).thenReturn(1);
        when(writer.markActionPaid(41L, "PAID", "EX_RIGHTS_APPLIED", now)).thenReturn(1);

        assertThrows(IllegalStateException.class, service::applyDueCorporateActions);

        verify(accountWriter).creditCash(61L, new BigDecimal("10000.00"), now);
        verify(writer).markActionPaid(41L, "PAID", "EX_RIGHTS_APPLIED", now);
    }

}
