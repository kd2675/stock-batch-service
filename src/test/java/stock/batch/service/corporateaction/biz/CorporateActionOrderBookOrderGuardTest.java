package stock.batch.service.corporateaction.biz;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;
import stock.batch.service.batch.corporateaction.reader.CorporateActionOrderReader;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;

class CorporateActionOrderBookOrderGuardTest {

    @Test
    void cancelOpenOrderBookOrders_locksAccountHoldingOrderThenReleasesInAccountOrder() {
        CorporateActionOrderReader reader = mock(CorporateActionOrderReader.class);
        CorporateActionWriter writer = mock(CorporateActionWriter.class);
        CorporateActionOrderBookOrderGuard guard = new CorporateActionOrderBookOrderGuard(
                reader,
                writer
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 0);
        DelistingOrderRow buyHighAccount = new DelistingOrderRow(1L, 3L, "DL001", "BUY", 10L, new BigDecimal("3000.00"));
        DelistingOrderRow sellLowAccount = new DelistingOrderRow(2L, 2L, "DL001", "SELL", 6L, BigDecimal.ZERO);
        DelistingOrderRow buyLowAccount = new DelistingOrderRow(3L, 1L, "DL001", "BUY", 10L, new BigDecimal("1000.00"));
        DelistingOrderRow sellLowSymbolA = new DelistingOrderRow(4L, 2L, "DL001", "SELL", 5L, BigDecimal.ZERO);
        List<DelistingOrderRow> candidates = List.of(buyHighAccount, sellLowAccount, buyLowAccount, sellLowSymbolA);
        when(reader.findOpenOrderBookOrderCandidates("DL001", 200))
                .thenReturn(candidates);
        when(reader.lockOpenOrderBookOrdersForUpdate(candidates))
                .thenReturn(List.of(buyHighAccount, sellLowAccount, buyLowAccount, sellLowSymbolA));
        when(writer.cancelOrders(List.of(1L, 2L, 3L, 4L), now)).thenReturn(4);
        when(writer.creditCashChunk(java.util.Map.of(
                1L, new BigDecimal("1000.00"),
                3L, new BigDecimal("3000.00")
        ), now)).thenReturn(2);
        when(writer.releaseReservedSellQuantityChunk("DL001", java.util.Map.of(2L, 11L), now)).thenReturn(1);

        guard.cancelOpenOrderBookOrderChunk("DL001", now, 200);

        InOrder inOrder = org.mockito.Mockito.inOrder(reader, writer);
        inOrder.verify(reader).findOpenOrderBookOrderCandidates("DL001", 200);
        inOrder.verify(reader).lockAccountsForUpdate(candidates);
        inOrder.verify(reader).lockSellHoldingsForUpdate("DL001", candidates);
        inOrder.verify(reader).lockOpenOrderBookOrdersForUpdate(candidates);
        inOrder.verify(writer).cancelOrders(List.of(1L, 2L, 3L, 4L), now);
        inOrder.verify(writer).creditCashChunk(java.util.Map.of(
                1L, new BigDecimal("1000.00"),
                3L, new BigDecimal("3000.00")
        ), now);
        inOrder.verify(writer).releaseReservedSellQuantityChunk("DL001", java.util.Map.of(2L, 11L), now);
    }
}
