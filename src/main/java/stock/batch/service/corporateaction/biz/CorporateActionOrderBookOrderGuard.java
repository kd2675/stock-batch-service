package stock.batch.service.corporateaction.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;
import stock.batch.service.batch.corporateaction.reader.CorporateActionOrderReader;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;
import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;

@Component
@RequiredArgsConstructor
class CorporateActionOrderBookOrderGuard {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final CorporateActionOrderReader corporateActionOrderReader;
    private final CorporateActionWriter corporateActionWriter;
    private final AutoParticipantFundingBudgetService fundingBudgetService;

    <T> Set<String> findSymbolsWithOpenOrders(List<T> rows, Function<T, String> symbolExtractor) {
        return corporateActionOrderReader.findSymbolsWithOpenOrderBookOrders(rows.stream().map(symbolExtractor).toList());
    }

    int cancelOpenOrderBookOrderChunk(String symbol, LocalDateTime now, int limit) {
        List<DelistingOrderRow> candidates = corporateActionOrderReader.findOpenOrderBookOrderCandidates(symbol, limit);
        if (candidates.isEmpty()) {
            return 0;
        }
        corporateActionOrderReader.lockAccountsForUpdate(candidates);
        corporateActionOrderReader.lockSellHoldingsForUpdate(symbol, candidates);
        List<DelistingOrderRow> orders = corporateActionOrderReader.lockOpenOrderBookOrdersForUpdate(candidates);
        if (orders.size() != candidates.size()) {
            throw new IllegalStateException(
                    "Delisting orders changed after exact primary-key lock: expected=%d, actual=%d"
                            .formatted(candidates.size(), orders.size())
            );
        }
        int cancelledCount = corporateActionWriter.cancelOrders(
                orders.stream().map(DelistingOrderRow::id).toList(),
                now
        );
        if (cancelledCount != orders.size()) {
            throw new IllegalStateException(
                    "Delisting order cancellation count mismatch: expected=%d, actual=%d"
                            .formatted(orders.size(), cancelledCount)
            );
        }
        fundingBudgetService.releaseCancelledOrderBudgets(
                orders.stream().map(DelistingOrderRow::id).toList(),
                now
        );
        releaseReservedAssets(symbol, orders, now);
        return cancelledCount;
    }

    private void releaseReservedAssets(
            String symbol,
            List<DelistingOrderRow> cancelledOrders,
            LocalDateTime now
    ) {
        Map<Long, BigDecimal> reservedCashByAccount = new TreeMap<>();
        Map<Long, Long> reservedSellQuantityByAccount = new TreeMap<>();

        for (DelistingOrderRow order : cancelledOrders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                reservedCashByAccount.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
            }
            if (SELL.equals(order.side()) && order.remainingQuantity() > 0) {
                reservedSellQuantityByAccount.merge(order.accountId(), order.remainingQuantity(), Long::sum);
            }
        }

        if (!reservedCashByAccount.isEmpty()) {
            int creditedCount = corporateActionWriter.creditCashChunk(reservedCashByAccount, now);
            if (creditedCount != reservedCashByAccount.size()) {
                throw new IllegalStateException(
                        "Delisting buy reservation release count mismatch: expected=%d, actual=%d"
                                .formatted(reservedCashByAccount.size(), creditedCount)
                );
            }
        }
        if (!reservedSellQuantityByAccount.isEmpty()) {
            int releasedCount = corporateActionWriter.releaseReservedSellQuantityChunk(
                    symbol,
                    reservedSellQuantityByAccount,
                    now
            );
            if (releasedCount != reservedSellQuantityByAccount.size()) {
                throw new IllegalStateException(
                        "Delisting sell reservation release count mismatch: expected=%d, actual=%d"
                                .formatted(reservedSellQuantityByAccount.size(), releasedCount)
                );
            }
        }
    }
}
