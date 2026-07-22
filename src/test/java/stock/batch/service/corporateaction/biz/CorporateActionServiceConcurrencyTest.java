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
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorporateActionServiceConcurrencyTest {

    @Test
    void processCapitalIncreaseAutoSubscriptionStep_actionFailureFailsStepBeforePaymentTransition() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        CorporateActionReader reader = mock(CorporateActionReader.class);
        CorporateActionOrderBookOrderGuard orderGuard = mock(CorporateActionOrderBookOrderGuard.class);
        CorporateActionPriceWriter priceWriter = mock(CorporateActionPriceWriter.class);
        CorporateActionWriter writer = mock(CorporateActionWriter.class);
        CorporateActionAccountWriter accountWriter = mock(CorporateActionAccountWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
        CorporateActionTransactionExecutor transactionExecutor = mock(CorporateActionTransactionExecutor.class);
        CorporateActionProcessingLedger processingLedger = mock(CorporateActionProcessingLedger.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        CorporateActionService service = new CorporateActionService(
                reader,
                orderGuard,
                priceWriter,
                writer,
                accountWriter,
                clockService,
                sessionService,
                transactionExecutor,
                processingLedger,
                marketSessionFenceService
        );
        CapitalIncreaseSubscriptionActionRow action = new CapitalIncreaseSubscriptionActionRow(
                11L,
                "005930",
                "PUBLIC_OFFERING",
                100L,
                new BigDecimal("50000.00")
        );
        when(reader.existsCompletedMarketCloseRun(today)).thenReturn(true);
        when(reader.findOpenCapitalIncreaseSubscriptions(
                today,
                "PAID_IN_CAPITAL_INCREASE",
                List.of("ANNOUNCED", "EX_RIGHTS_APPLIED"),
                25
        )).thenReturn(List.of(action));
        when(reader.findEventProfilePolicies()).thenThrow(new IllegalStateException("Unknown event profile"));

        assertThrows(
                IllegalStateException.class,
                () -> service.processCapitalIncreaseAutoSubscriptionStep(today)
        );

        verify(reader, never()).findDueCapitalIncreasePayments(any(), any(), any(), any(), anyInt());
    }

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
        CorporateActionProcessingLedger processingLedger = mock(CorporateActionProcessingLedger.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> actionUnit = invocation.getArgument(0);
            return actionUnit.get();
        });
        when(transactionExecutor.executeValue(any())).thenAnswer(invocation -> {
            Supplier<?> actionUnit = invocation.getArgument(0);
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
                transactionExecutor,
                processingLedger,
                marketSessionFenceService
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
        when(reader.findOpenCapitalIncreaseSubscriptions(
                today,
                "PAID_IN_CAPITAL_INCREASE",
                List.of("ANNOUNCED", "EX_RIGHTS_APPLIED"),
                25
        ))
                .thenReturn(List.of(action));
        when(reader.findEventProfilePolicies()).thenReturn(List.of());
        when(reader.findCapitalIncreaseSubscriptionForUpdate(
                11L,
                today,
                "PAID_IN_CAPITAL_INCREASE",
                "EX_RIGHTS_APPLIED",
                "SHAREHOLDER_ALLOCATION"
        )).thenReturn(Optional.of(action));
        when(reader.findShareholderAllocationAutoCandidateChunk(
                11L,
                "ANNOUNCED",
                "shareholder-allocation-auto-subscription",
                today,
                0L,
                200
        )).thenReturn(List.of(candidate));
        when(reader.findActiveAccountCashForUpdate(List.of(31L)))
                .thenReturn(java.util.Map.of(31L, new BigDecimal("10000000.00")));
        when(reader.findAnnouncedEntitlementShareQuantityForUpdate(List.of(21L), 11L, "ANNOUNCED"))
                .thenReturn(java.util.Map.of());
        when(processingLedger.completeAccounts(anyLong(), any(), any(), any(), any())).thenReturn(1);

        service.applyDueCorporateActions();

        InOrder lockOrder = inOrder(reader);
        lockOrder.verify(reader).findCapitalIncreaseSubscriptionForUpdate(
                11L,
                today,
                "PAID_IN_CAPITAL_INCREASE",
                "EX_RIGHTS_APPLIED",
                "SHAREHOLDER_ALLOCATION"
        );
        lockOrder.verify(reader).findActiveAccountCashForUpdate(List.of(31L));
        lockOrder.verify(reader).findAnnouncedEntitlementShareQuantityForUpdate(List.of(21L), 11L, "ANNOUNCED");
        verify(accountWriter, never()).withdrawCashForSubscriptionChunk(any(), any());
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
        CorporateActionProcessingLedger processingLedger = mock(CorporateActionProcessingLedger.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> actionUnit = invocation.getArgument(0);
            return actionUnit.get();
        });
        when(transactionExecutor.executeValue(any())).thenAnswer(invocation -> {
            Supplier<?> actionUnit = invocation.getArgument(0);
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
                transactionExecutor,
                processingLedger,
                marketSessionFenceService
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
                List.of("ANNOUNCED", "EX_RIGHTS_APPLIED"),
                25
        )).thenReturn(List.of(action));
        when(reader.findEventProfilePolicies()).thenThrow(new IllegalStateException("Unknown event profile"));
        when(reader.findDueCashDividendActionIds(today, "CASH_DIVIDEND", "EX_RIGHTS_APPLIED", 25))
                .thenReturn(List.of(41L));
        when(reader.lockDueActionForUpdate(41L, today, "CASH_DIVIDEND", "EX_RIGHTS_APPLIED", "payment_date"))
                .thenReturn(true);
        when(reader.findDividendEntitlementChunk(41L, "ANNOUNCED", 200))
                .thenReturn(List.of(dividend), List.of());
        when(accountWriter.lockDividendAccountsForUpdate(List.of(dividend))).thenReturn(1);
        when(accountWriter.creditDividendCashChunk(List.of(dividend), now)).thenReturn(1);
        when(accountWriter.recordDividendPaymentCashFlowChunk(41L, List.of(dividend), today, now)).thenReturn(1);
        when(accountWriter.markDividendEntitlementChunkPaid(
                List.of(dividend), "PAID", "ANNOUNCED", now
        )).thenReturn(1);
        when(processingLedger.completeAccounts(
                anyLong(), any(), any(), any(), any()
        )).thenReturn(1);
        when(writer.markActionPaid(41L, "PAID", "EX_RIGHTS_APPLIED", now)).thenReturn(1);

        assertThrows(IllegalStateException.class, service::applyDueCorporateActions);

        verify(accountWriter).creditDividendCashChunk(List.of(dividend), now);
        verify(writer).markActionPaid(41L, "PAID", "EX_RIGHTS_APPLIED", now);
    }

}
