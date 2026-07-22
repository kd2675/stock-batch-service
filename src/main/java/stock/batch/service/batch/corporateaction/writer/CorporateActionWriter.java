package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CorporateActionWriter {

    private final JdbcTemplate jdbcTemplate;

    public int freezeEntitlementSnapshot(
            long actionId,
            long closeCycleId,
            long closeRunId,
            String sourceStatus
    ) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set entitlement_close_cycle_id = ?,
                       entitlement_close_run_id = ?
                 where id = ?
                   and status = ?
                   and entitlement_close_cycle_id is null
                   and entitlement_close_run_id is null
                """,
                closeCycleId,
                closeRunId,
                actionId,
                sourceStatus
        );
    }

    public int markActionExRightsApplied(long actionId, String nextStatus, String sourceStatus, LocalDateTime appliedAt) {
        return markCorporateActionTimestamp(actionId, nextStatus, sourceStatus, "applied_at", appliedAt);
    }

    public int markActionExRightsAppliedWithPrices(
            long actionId,
            String nextStatus,
            String sourceStatus,
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice,
            LocalDateTime appliedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       base_price = ?,
                       theoretical_ex_rights_price = ?,
                       applied_at = ?
                 where id = ?
                   and status = ?
                """,
                nextStatus,
                basePrice,
                theoreticalExRightsPrice,
                appliedAt,
                actionId,
                sourceStatus
        );
    }

    public int markActionPaid(long actionId, String paidStatus, String sourceStatus, LocalDateTime paidAt) {
        return markCorporateActionTimestamp(actionId, paidStatus, sourceStatus, "paid_at", paidAt);
    }

    public int markActionListed(long actionId, String listedStatus, String sourceStatus, LocalDateTime listedAt) {
        return markCorporateActionTimestamp(actionId, listedStatus, sourceStatus, "listed_at", listedAt);
    }

    public int markActionDelisted(long actionId, String delistedStatus, String sourceStatus, LocalDateTime appliedAt) {
        return markCorporateActionTimestamp(actionId, delistedStatus, sourceStatus, "applied_at", appliedAt);
    }

    public int cancelOrders(List<Long> orderIds, LocalDateTime updatedAt) {
        List<Long> orderedOrderIds = orderIds.stream().distinct().sorted().toList();
        if (orderedOrderIds.isEmpty()) {
            return 0;
        }
        if (orderedOrderIds.size() != orderIds.size()) {
            throw new IllegalArgumentException("Corporate action cancellation chunk contains duplicate order ids");
        }
        String placeholders = String.join(",", Collections.nCopies(orderedOrderIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(orderedOrderIds.size() + 1);
        parameters.add(updatedAt);
        parameters.addAll(orderedOrderIds);
        return jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where id in (%s)
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public int creditCashChunk(Map<Long, BigDecimal> cashByAccountId, LocalDateTime updatedAt) {
        if (cashByAccountId.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Long, BigDecimal>> entries = cashByAccountId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        String cashCases = String.join(" ", Collections.nCopies(entries.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(entries.size(), "?"));
        List<Object> parameters = new ArrayList<>(entries.size() * 3 + 1);
        for (Map.Entry<Long, BigDecimal> entry : entries) {
            parameters.add(entry.getKey());
            parameters.add(entry.getValue());
        }
        parameters.add(updatedAt);
        entries.forEach(entry -> parameters.add(entry.getKey()));
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                """.formatted(cashCases, placeholders),
                parameters.toArray()
        );
    }

    public int releaseReservedSellQuantityChunk(
            String symbol,
            Map<Long, Long> quantityByAccountId,
            LocalDateTime updatedAt
    ) {
        if (quantityByAccountId.isEmpty()) {
            return 0;
        }
        List<Map.Entry<Long, Long>> entries = quantityByAccountId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        String quantityCases = String.join(" ", Collections.nCopies(entries.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(entries.size(), "?"));
        List<Object> parameters = new ArrayList<>(entries.size() * 3 + 2);
        for (Map.Entry<Long, Long> entry : entries) {
            parameters.add(entry.getKey());
            parameters.add(entry.getValue());
        }
        parameters.add(updatedAt);
        parameters.add(symbol);
        entries.forEach(entry -> parameters.add(entry.getKey()));
        return jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = greatest(
                           0,
                           reserved_quantity - case account_id %s else 0 end
                       ),
                       updated_at = ?
                 where symbol = ?
                   and account_id in (%s)
                """.formatted(quantityCases, placeholders),
                parameters.toArray()
        );
    }

    public int delistInstrument(String symbol, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set enabled = false,
                       tradable_shares = 0,
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public void haltOrderBookMarket(String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_order_book_market_config
                   set enabled = false,
                       market_status = 'HALTED',
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public void disableAutoMarket(String symbol, LocalDateTime updatedAt) {
        disableSymbolConfig("stock_auto_market_config", symbol, updatedAt);
    }

    public void disableListingAutoAccount(String symbol, LocalDateTime updatedAt) {
        disableSymbolConfig("stock_listing_auto_account_config", symbol, updatedAt);
    }

    public void disableParticipantSymbolConfigs(String symbol, LocalDateTime updatedAt) {
        disableSymbolConfig("stock_auto_participant_symbol_config", symbol, updatedAt);
    }

    private int markCorporateActionTimestamp(
            long actionId,
            String nextStatus,
            String sourceStatus,
            String timestampColumn,
            LocalDateTime timestamp
    ) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       %s = ?
                 where id = ?
                   and status = ?
                """.formatted(timestampColumn),
                nextStatus,
                timestamp,
                actionId,
                sourceStatus
        );
    }

    private void disableSymbolConfig(String tableName, String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update %s
                   set enabled = false,
                       updated_at = ?
                 where symbol = ?
                """.formatted(tableName),
                updatedAt,
                symbol
        );
    }

    public int addIssuedAndTradableShares(String symbol, long shareQuantity, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = issued_shares + ?,
                       tradable_shares = tradable_shares + ?,
                       updated_at = ?
                 where symbol = ?
                """,
                shareQuantity,
                shareQuantity,
                updatedAt,
                symbol
        );
    }

    public int multiplyInstrumentShares(String symbol, int multiplier, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = issued_shares * ?,
                       tradable_shares = tradable_shares * ?,
                       updated_at = ?
                 where symbol = ?
                """,
                multiplier,
                multiplier,
                updatedAt,
                symbol
        );
    }

    public int lockHoldingChunkForSplit(String symbol, List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return 0;
        }
        List<Long> orderedAccountIds = accountIds.stream().distinct().sorted().toList();
        if (orderedAccountIds.size() != accountIds.size()) {
            throw new IllegalArgumentException("Stock split chunk contains duplicate account ids");
        }
        String placeholders = String.join(",", Collections.nCopies(orderedAccountIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(orderedAccountIds.size() + 1);
        parameters.add(symbol);
        parameters.addAll(orderedAccountIds);
        return jdbcTemplate.queryForList(
                """
                select id
                  from stock_holding
                 where symbol = ?
                   and account_id in (%s)
                 order by account_id asc, symbol asc
                 for update
                """.formatted(placeholders),
                Long.class,
                parameters.toArray()
        ).size();
    }

    public int multiplyHoldingChunkForSplit(
            String symbol,
            List<Long> accountIds,
            int multiplier,
            BigDecimal priceDivisor,
            LocalDateTime updatedAt
    ) {
        if (accountIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(accountIds.size() + 5);
        parameters.add(multiplier);
        parameters.add(multiplier);
        parameters.add(priceDivisor);
        parameters.add(updatedAt);
        parameters.add(symbol);
        parameters.addAll(accountIds);
        return jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity * ?,
                       reserved_quantity = reserved_quantity * ?,
                       average_price = average_price / ?,
                       updated_at = ?
                 where symbol = ?
                   and account_id in (%s)
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public void adjustPriceForSplit(String symbol, BigDecimal priceDivisor, LocalDateTime priceTime) {
        jdbcTemplate.update(
                """
                update stock_price
                   set current_price = current_price / ?,
                       previous_close = previous_close / ?,
                       price_time = ?,
                       provider = 'corporate-action-split'
                 where symbol = ?
                """,
                priceDivisor,
                priceDivisor,
                priceTime,
                symbol
        );
    }

}
