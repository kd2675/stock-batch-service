package stock.batch.service.corporateaction.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        List<DelistingOrderRow> cancelledOrders = new ArrayList<>();
        for (DelistingOrderRow order : orders) {
            if (corporateActionWriter.cancelOrder(order.id(), now)) {
                cancelledOrders.add(order);
            }
        }
        releaseReservedAssets(cancelledOrders, now);
    }

    private void releaseReservedAssets(List<DelistingOrderRow> cancelledOrders, LocalDateTime now) {
        Map<Long, BigDecimal> reservedCashByAccount = new TreeMap<>();
        Map<ReservedHoldingKey, Long> reservedSellQuantityByHolding = new TreeMap<>();

        for (DelistingOrderRow order : cancelledOrders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                reservedCashByAccount.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
            }
            if (SELL.equals(order.side()) && order.remainingQuantity() > 0) {
                reservedSellQuantityByHolding.merge(
                        new ReservedHoldingKey(order.accountId(), order.symbol()),
                        order.remainingQuantity(),
                        Long::sum
                );
            }
        }

        reservedCashByAccount.forEach((accountId, reservedCash) ->
                corporateActionAccountWriter.creditCash(accountId, reservedCash, now));
        reservedSellQuantityByHolding.forEach((key, quantity) ->
                corporateActionWriter.releaseReservedSellQuantity(key.accountId(), key.symbol(), quantity, now));
    }

    private record ReservedHoldingKey(long accountId, String symbol) implements Comparable<ReservedHoldingKey> {

        @Override
        public int compareTo(ReservedHoldingKey other) {
            int accountComparison = Long.compare(accountId, other.accountId);
            if (accountComparison != 0) {
                return accountComparison;
            }
            return symbol.compareTo(other.symbol);
        }
    }
}
