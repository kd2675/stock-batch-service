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
import stock.batch.service.batch.corporateaction.writer.CorporateActionAccountWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;

class CorporateActionOrderBookOrderGuardTest {

    @Test
    void cancelOpenOrderBookOrders_releasesCashAndHoldingsInAccountOrderAfterCancellingOrders() {
        CorporateActionOrderReader reader = mock(CorporateActionOrderReader.class);
        CorporateActionAccountWriter accountWriter = mock(CorporateActionAccountWriter.class);
        CorporateActionWriter writer = mock(CorporateActionWriter.class);
        CorporateActionOrderBookOrderGuard guard = new CorporateActionOrderBookOrderGuard(
                reader,
                accountWriter,
                writer
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 0);
        DelistingOrderRow buyHighAccount = new DelistingOrderRow(1L, 3L, "DL001", "BUY", 10L, new BigDecimal("3000.00"));
        DelistingOrderRow sellLowSymbolB = new DelistingOrderRow(2L, 2L, "DL002", "SELL", 6L, BigDecimal.ZERO);
        DelistingOrderRow buyLowAccount = new DelistingOrderRow(3L, 1L, "DL001", "BUY", 10L, new BigDecimal("1000.00"));
        DelistingOrderRow sellLowSymbolA = new DelistingOrderRow(4L, 2L, "DL001", "SELL", 5L, BigDecimal.ZERO);
        when(reader.findOpenOrderBookOrdersForUpdate("DL001"))
                .thenReturn(List.of(buyHighAccount, sellLowSymbolB, buyLowAccount, sellLowSymbolA));
        when(writer.cancelOrder(1L, now)).thenReturn(true);
        when(writer.cancelOrder(2L, now)).thenReturn(true);
        when(writer.cancelOrder(3L, now)).thenReturn(true);
        when(writer.cancelOrder(4L, now)).thenReturn(true);

        guard.cancelOpenOrderBookOrders("DL001", now);

        InOrder inOrder = org.mockito.Mockito.inOrder(writer, accountWriter);
        inOrder.verify(writer).cancelOrder(1L, now);
        inOrder.verify(writer).cancelOrder(2L, now);
        inOrder.verify(writer).cancelOrder(3L, now);
        inOrder.verify(writer).cancelOrder(4L, now);
        inOrder.verify(accountWriter).creditCash(1L, new BigDecimal("1000.00"), now);
        inOrder.verify(accountWriter).creditCash(3L, new BigDecimal("3000.00"), now);
        inOrder.verify(writer).releaseReservedSellQuantity(2L, "DL001", 5L, now);
        inOrder.verify(writer).releaseReservedSellQuantity(2L, "DL002", 6L, now);
    }
}
