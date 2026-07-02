package stock.batch.service.corporateaction.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;
import stock.batch.service.batch.corporateaction.reader.CorporateActionOrderReader;
import stock.batch.service.batch.corporateaction.writer.CorporateActionAccountWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;

@Component
@RequiredArgsConstructor
class CorporateActionOrderBookOrderGuard {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final CorporateActionOrderReader corporateActionOrderReader;
    private final CorporateActionAccountWriter corporateActionAccountWriter;
    private final CorporateActionWriter corporateActionWriter;

    <T> Set<String> findSymbolsWithOpenOrders(List<T> rows, Function<T, String> symbolExtractor) {
        return corporateActionOrderReader.findSymbolsWithOpenOrderBookOrders(rows.stream().map(symbolExtractor).toList());
    }

    void cancelOpenOrderBookOrders(String symbol, LocalDateTime now) {
        List<DelistingOrderRow> orders = corporateActionOrderReader.findOpenOrderBookOrdersForUpdate(symbol);
        for (DelistingOrderRow order : orders) {
            releaseReservedAssets(order, now);
            corporateActionWriter.cancelOrder(order.id(), now);
        }
    }

    private void releaseReservedAssets(DelistingOrderRow order, LocalDateTime now) {
        if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
            corporateActionAccountWriter.creditCash(order.accountId(), order.reservedCash(), now);
        }
        if (SELL.equals(order.side()) && order.remainingQuantity() > 0) {
            corporateActionWriter.releaseReservedSellQuantity(
                    order.accountId(),
                    order.symbol(),
                    order.remainingQuantity(),
                    now
            );
        }
    }
}
