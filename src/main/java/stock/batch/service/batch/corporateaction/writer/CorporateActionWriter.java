package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        return addIssuedAndTradableShares(
                symbol,
                shareQuantity,
                shareQuantity,
                updatedAt
        );
    }

    public int addIssuedAndTradableShares(
            String symbol,
            long issuedShareQuantity,
            long tradableShareQuantity,
            LocalDateTime updatedAt
    ) {
        if (issuedShareQuantity < 0L
                || tradableShareQuantity < 0L
                || tradableShareQuantity > issuedShareQuantity) {
            throw new IllegalArgumentException(
                    "Issued-share increase must contain a valid tradable-share subset"
            );
        }
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = issued_shares + ?,
                       tradable_shares = tradable_shares + ?,
                       updated_at = ?
                 where symbol = ?
                """,
                issuedShareQuantity,
                tradableShareQuantity,
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

    /**
     * Keeps quantity-denominated market-role policies economically neutral across a split.
     * Historical allocation and decision ledgers deliberately remain in their original units.
     */
    public void multiplyAutomaticMarketQuantitiesForSplit(
            String symbol,
            int multiplier,
            LocalDateTime updatedAt
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Stock split symbol is required");
        }
        if (multiplier <= 1) {
            throw new IllegalArgumentException("Stock split multiplier must be greater than one");
        }
        jdbcTemplate.update(
                """
                update stock_institution_symbol_mandate
                   set reference_daily_volume = reference_daily_volume * ?,
                       updated_at = ?
                 where symbol = ?
                """,
                multiplier,
                updatedAt,
                symbol
        );
        jdbcTemplate.update(
                """
                update stock_liquidity_mandate
                   set max_order_quantity = max_order_quantity * ?,
                       reference_daily_volume = reference_daily_volume * ?,
                       target_inventory_quantity = target_inventory_quantity * ?,
                       inventory_band_quantity = inventory_band_quantity * ?,
                       updated_at = ?
                 where symbol = ?
                """,
                multiplier,
                multiplier,
                multiplier,
                multiplier,
                updatedAt,
                symbol
        );
        jdbcTemplate.update(
                """
                update stock_underwriting_contract
                   set issue_price = issue_price / ?,
                       stabilization_quantity_limit = stabilization_quantity_limit * ?,
                       updated_at = ?
                 where symbol = ?
                   and status in ('ALLOCATED', 'STABILIZING')
                """,
                multiplier,
                multiplier,
                updatedAt,
                symbol
        );
        jdbcTemplate.update(
                """
                update stock_underwriting_daily_supply_state
                   set reference_daily_volume = reference_daily_volume * ?,
                       submission_quantity_limit = submission_quantity_limit * ?,
                       submitted_quantity = submitted_quantity * ?,
                       updated_at = ?
                 where underwriting_contract_id in (
                     select id
                       from stock_underwriting_contract
                      where symbol = ?
                        and status in ('ALLOCATED', 'STABILIZING')
                 )
                """,
                multiplier,
                multiplier,
                multiplier,
                updatedAt,
                symbol
        );
    }

    public long creditFreeShareRoundingResidualToCustody(
            long actionId,
            String symbol,
            long residualQuantity,
            LocalDate effectiveBusinessDate,
            LocalDateTime updatedAt
    ) {
        if (residualQuantity <= 0L) {
            return 0L;
        }
        List<Long> custodyAccountIds = jdbcTemplate.queryForList(
                """
                select account.id
                  from stock_account account
                  join stock_market_participant_account participant_account
                    on participant_account.account_id = account.id
                  join stock_market_participant participant
                    on participant.id = participant_account.participant_id
                 where account.user_key = 'stock-system-custody'
                   and account.participant_category = 'SYSTEM_CUSTODY'
                   and account.status = 'ACTIVE'
                   and account.self_trade_group_id = 'SYSTEM_CUSTODY:DEFAULT'
                   and participant.participant_code = 'SYSTEM_CUSTODY'
                   and participant.participant_type = 'SYSTEM_CUSTODY'
                   and participant.status = 'ACTIVE'
                   and participant.self_trade_group_id = 'SYSTEM_CUSTODY:DEFAULT'
                   and participant_account.account_role = 'SYSTEM_CUSTODY'
                   and participant_account.desk_code = 'DEFAULT'
                   and participant_account.status = 'ACTIVE'
                   and participant_account.effective_from <= ?
                   and (
                       participant_account.effective_to is null
                       or participant_account.effective_to >= ?
                   )
                 order by account.id
                 for update
                """,
                Long.class,
                effectiveBusinessDate,
                effectiveBusinessDate
        );
        if (custodyAccountIds.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one active default SYSTEM_CUSTODY account is required "
                            + "for free-share residuals"
            );
        }
        long custodyAccountId = custodyAccountIds.getFirst();
        BigDecimal referencePrice = jdbcTemplate.queryForObject(
                """
                select current_price
                  from stock_price
                 where symbol = ?
                """,
                BigDecimal.class,
                symbol
        );
        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "A positive reference price is required for free-share residual custody: "
                            + symbol
            );
        }
        List<Map<String, Object>> holdings = jdbcTemplate.queryForList(
                """
                select id, quantity, average_price
                  from stock_holding
                 where account_id = ?
                   and symbol = ?
                 for update
                """,
                custodyAccountId,
                symbol
        );
        if (holdings.isEmpty()) {
            jdbcTemplate.update(
                    """
                    insert into stock_holding(
                        account_id, symbol, quantity, reserved_quantity,
                        average_price, updated_at
                    ) values (?, ?, ?, 0, ?, ?)
                    """,
                    custodyAccountId,
                    symbol,
                    residualQuantity,
                    referencePrice,
                    updatedAt
            );
        } else if (holdings.size() == 1) {
            jdbcTemplate.update(
                    """
                    update stock_holding
                       set average_price = case
                               when quantity > 0
                               then (average_price * quantity) / (quantity + ?)
                               else ?
                           end,
                           quantity = quantity + ?,
                           updated_at = ?
                     where account_id = ?
                       and symbol = ?
                    """,
                    residualQuantity,
                    referencePrice,
                    residualQuantity,
                    updatedAt,
                    custodyAccountId,
                    symbol
            );
        } else {
            throw new IllegalStateException(
                    "SYSTEM_CUSTODY contains duplicate holdings for " + symbol
            );
        }
        int insertedAudit = jdbcTemplate.update(
                """
                insert into stock_security_allocation_ledger(
                    idempotency_key, event_type, corporate_action_id,
                    underwriting_contract_id, source_account_id,
                    destination_account_id, symbol, quantity, unit_price,
                    allocation_reason, tradability_status,
                    effective_business_date, unlock_business_date, created_at
                ) values (?, 'CAPITAL_INCREASE', ?, null, null, ?, ?, ?, 0.00,
                          'CORPORATE_ACTION_ALLOCATION', 'LOCKED', ?, null, ?)
                """,
                "CORPORATE_ACTION:" + actionId + ":ROUNDING_CUSTODY",
                actionId,
                custodyAccountId,
                symbol,
                residualQuantity,
                effectiveBusinessDate,
                updatedAt
        );
        if (insertedAudit != 1) {
            throw new IllegalStateException(
                    "Free-share residual allocation audit count mismatch: " + insertedAudit
            );
        }
        return residualQuantity;
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
