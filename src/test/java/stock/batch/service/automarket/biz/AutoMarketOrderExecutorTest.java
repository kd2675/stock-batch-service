package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

class AutoMarketOrderExecutorTest {

    @Test
    void placeOrders_closedSessionDropsOrdersBeforeReservationDatabaseAccess() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, fenceService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 1)
        );
        when(fenceService.lockOpenOrderBookFences(List.of("STOCK001"))).thenReturn(Optional.empty());

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(result.droppedOrderCount(AutoMarketOrderDropReason.SESSION_CLOSED)).isEqualTo(1);
        verifyNoInteractions(orderReader, writer);
    }

    @Test
    void placeOrders_plannedOrderChunkAboveLimit_throwsBeforeDatabaseAccess() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = Collections.nCopies(
                801,
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 1)
        );

        assertThatThrownBy(() -> executor.placeOrders(orders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("801 > 800");
    }

    @Test
    void placeOrders_sameAccountBuyOrders_reservesCashOnceAndBatchInsertsAcceptedOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 2),
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1200.00"), 3)
        );
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(2);

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isEqualTo(2);
        assertThat(result.reservedBuyCount()).isEqualTo(2);
        assertThat(result.failedReserveCount()).isZero();
        verify(writer).reserveBuyCashChunk(Map.of(1L, new BigDecimal("5600.00")), now);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AutoMarketWriter.LimitOrderInsert>> insertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertLimitOrders(insertsCaptor.capture(), eq(now));
        assertThat(insertsCaptor.getValue())
                .extracting(AutoMarketWriter.LimitOrderInsert::reservedCash)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("3600.00"));
    }

    @Test
    void placeOrders_buyReservations_lockAccountsInAscendingAccountIdOrder() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(2L, "STOCK001", "BUY", new BigDecimal("1000.00"), 1),
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 1)
        );
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(2);

        executor.placeOrders(orders);

        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).lockAccountReservationStatesForUpdate(List.of(1L, 2L));
        inOrder.verify(writer).reserveBuyCashChunk(
                Map.of(1L, new BigDecimal("1000.00"), 2L, new BigDecimal("1000.00")),
                now
        );
    }

    @Test
    void placeOrders_sameHoldingSellOrders_reservesQuantityOnceAndBatchInsertsAcceptedOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1000.00"), 2),
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1200.00"), 3)
        );
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(2);

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isEqualTo(2);
        assertThat(result.reservedSellCount()).isEqualTo(2);
        assertThat(result.failedReserveCount()).isZero();
        verify(writer).reserveSellQuantityChunk(
                Map.of(new AutoMarketWriter.HoldingReservationKey(1L, "STOCK001"), 5L),
                now
        );
    }

    @Test
    void placeOrders_sellReservations_lockHoldingsInAscendingAccountAndSymbolOrder() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(2L, "STOCK002", "SELL", new BigDecimal("1000.00"), 1),
                new AutoMarketPlannedOrder(1L, "STOCK002", "SELL", new BigDecimal("1000.00"), 1),
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1000.00"), 1)
        );
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(3);

        executor.placeOrders(orders);

        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).lockAccountReservationStatesForUpdate(List.of(1L, 2L));
        inOrder.verify(writer).lockHoldingReservationStatesForUpdate(List.of(
                new AutoMarketWriter.HoldingReservationKey(1L, "STOCK001"),
                new AutoMarketWriter.HoldingReservationKey(1L, "STOCK002"),
                new AutoMarketWriter.HoldingReservationKey(2L, "STOCK002")
        ));
        inOrder.verify(writer).reserveSellQuantityChunk(
                Map.of(
                        new AutoMarketWriter.HoldingReservationKey(1L, "STOCK001"), 1L,
                        new AutoMarketWriter.HoldingReservationKey(1L, "STOCK002"), 1L,
                        new AutoMarketWriter.HoldingReservationKey(2L, "STOCK002"), 1L
                ),
                now
        );
    }

    @Test
    void placeOrders_reserveFailureSkipsRejectedOrdersAndInsertsOnlyAcceptedOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 2),
                new AutoMarketPlannedOrder(2L, "STOCK001", "BUY", new BigDecimal("1200.00"), 3)
        );
        when(writer.lockAccountReservationStatesForUpdate(List.of(1L, 2L))).thenReturn(Map.of(
                1L, new AutoMarketWriter.AccountReservationState(1L, "ACTIVE", new BigDecimal("1999.00")),
                2L, new AutoMarketWriter.AccountReservationState(2L, "ACTIVE", new BigDecimal("3600.00"))
        ));
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(1);

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isEqualTo(1);
        assertThat(result.reservedBuyCount()).isEqualTo(1);
        assertThat(result.failedReserveCount()).isEqualTo(1);
        assertThat(result.droppedOrderCount(AutoMarketOrderDropReason.BUY_RESERVATION_FAILED)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AutoMarketWriter.LimitOrderInsert>> insertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertLimitOrders(insertsCaptor.capture(), eq(now));
        assertThat(insertsCaptor.getValue())
                .extracting(AutoMarketWriter.LimitOrderInsert::accountId)
                .containsExactly(2L);
    }

    @Test
    void placeOrders_sellReservationFailure_recordsSellReservationDropReason() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1000.00"), 2)
        );
        AutoMarketWriter.HoldingReservationKey key = new AutoMarketWriter.HoldingReservationKey(1L, "STOCK001");
        when(writer.lockHoldingReservationStatesForUpdate(List.of(key))).thenReturn(Map.of(
                key,
                new AutoMarketWriter.HoldingReservationState(key, 1L, 0L)
        ));

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.droppedOrderCount(AutoMarketOrderDropReason.SELL_RESERVATION_FAILED)).isEqualTo(1);
    }

    @Test
    void placeOrders_batchInsertMismatch_throwsToRollbackReservationTransaction() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 2)
        );
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(0);

        assertThatThrownBy(() -> executor.placeOrders(orders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Auto order batch insert count mismatch");
    }

    @Test
    void expireOrders_locksResourcesBeforeOrdersThenReleasesReservations() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = newOpenExecutor(orderReader, writer, now);
        AutoOrder buyHighAccount = new AutoOrder(1L, 3L, "STOCK001", "BUY", 10, 0, new BigDecimal("3000.00"));
        AutoOrder sellLowSymbolB = new AutoOrder(2L, 2L, "STOCK002", "SELL", 8, 2, BigDecimal.ZERO);
        AutoOrder buyLowAccount = new AutoOrder(3L, 1L, "STOCK001", "BUY", 10, 0, new BigDecimal("1000.00"));
        AutoOrder sellLowSymbolA = new AutoOrder(4L, 2L, "STOCK001", "SELL", 5, 0, BigDecimal.ZERO);
        List<AutoOrder> orders = List.of(buyHighAccount, sellLowSymbolB, buyLowAccount, sellLowSymbolA);
        when(orderReader.lockOpenOrdersForUpdate(orders)).thenReturn(orders);
        when(writer.cancelOpenOrders(orders, now)).thenReturn(4);

        int expiredCount = executor.expireOrders(orders, now);

        assertThat(expiredCount).isEqualTo(4);
        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).lockAccountsForUpdate(List.of(3L, 2L, 1L, 2L));
        inOrder.verify(writer).lockSellHoldingsForUpdate(orders);
        inOrder.verify(writer).cancelOpenOrders(orders, now);
        inOrder.verify(writer).creditCancelledBuyReservations(orders, now);
        inOrder.verify(writer).releaseCancelledSellReservations(orders, now);
    }

    private AutoMarketOrderExecutor newOpenExecutor(
            AutoMarketOrderReader orderReader,
            AutoMarketWriter writer,
            LocalDateTime now
    ) {
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        when(fenceService.lockOpenOrderBookFences(any())).thenReturn(Optional.of(
                new MarketSessionFenceService.MarketSessionApproval(
                        LocalDate.of(2026, 7, 3),
                        Map.of("STOCK001", 1L, "STOCK002", 1L),
                        now
                )
        ));
        when(writer.lockAccountReservationStatesForUpdate(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Long> accountIds = invocation.getArgument(0, List.class);
            Map<Long, AutoMarketWriter.AccountReservationState> states = new LinkedHashMap<>();
            accountIds.stream().distinct().sorted().forEach(accountId -> states.put(
                    accountId,
                    new AutoMarketWriter.AccountReservationState(
                            accountId,
                            "ACTIVE",
                            new BigDecimal("999999999999999.00")
                    )
            ));
            return states;
        });
        when(writer.lockHoldingReservationStatesForUpdate(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<AutoMarketWriter.HoldingReservationKey> keys = invocation.getArgument(0, List.class);
            Map<AutoMarketWriter.HoldingReservationKey, AutoMarketWriter.HoldingReservationState> states =
                    new LinkedHashMap<>();
            keys.forEach(key -> states.put(
                    key,
                    new AutoMarketWriter.HoldingReservationState(key, Long.MAX_VALUE, 0L)
            ));
            return states;
        });
        when(writer.reserveBuyCashChunk(any(), eq(now))).thenAnswer(invocation ->
                invocation.<Map<?, ?>>getArgument(0).size()
        );
        when(writer.reserveSellQuantityChunk(any(), eq(now))).thenAnswer(invocation ->
                invocation.<Map<?, ?>>getArgument(0).size()
        );
        return new AutoMarketOrderExecutor(orderReader, writer, fenceService);
    }
}
