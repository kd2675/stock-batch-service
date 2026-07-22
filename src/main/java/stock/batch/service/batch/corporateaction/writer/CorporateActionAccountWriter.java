package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.CapitalIncreaseSubscriptionDecision;
import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;

@Component
public class CorporateActionAccountWriter {

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysql;

    public CorporateActionAccountWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> databaseProductName(connection)
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    public int lockDividendAccountsForUpdate(List<DividendEntitlementRow> entitlements) {
        List<Long> accountIds = entitlements.stream()
                .map(DividendEntitlementRow::accountId)
                .distinct()
                .sorted()
                .toList();
        if (accountIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        return jdbcTemplate.queryForList(
                "select id from stock_account where id in (%s) order by id asc for update"
                        .formatted(placeholders),
                Long.class,
                accountIds.toArray()
        ).size();
    }

    public int creditDividendCashChunk(
            List<DividendEntitlementRow> entitlements,
            LocalDateTime updatedAt
    ) {
        if (entitlements.isEmpty()) {
            return 0;
        }
        String cases = String.join(" ", Collections.nCopies(entitlements.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(entitlements.size(), "?"));
        List<Object> parameters = new ArrayList<>(entitlements.size() * 3 + 1);
        for (DividendEntitlementRow entitlement : entitlements) {
            parameters.add(entitlement.accountId());
            parameters.add(entitlement.cashAmount());
        }
        parameters.add(updatedAt);
        entitlements.forEach(entitlement -> parameters.add(entitlement.accountId()));
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                """.formatted(cases, placeholders),
                parameters.toArray()
        );
    }

    public int recordDividendPaymentCashFlowChunk(
            long actionId,
            List<DividendEntitlementRow> entitlements,
            LocalDate effectiveBusinessDate,
            LocalDateTime createdAt
    ) {
        if (entitlements.isEmpty()) {
            return 0;
        }
        String values = String.join(",", Collections.nCopies(
                entitlements.size(),
                "(?, 'DEPOSIT', ?, 'DIVIDEND_PAYMENT', 'CORPORATE_ACTION', ?, ?, ?, ?)"
        ));
        List<Object> parameters = new ArrayList<>(entitlements.size() * 7);
        for (DividendEntitlementRow entitlement : entitlements) {
            parameters.add(entitlement.accountId());
            parameters.add(entitlement.cashAmount());
            parameters.add(actionId);
            parameters.add(entitlement.id());
            parameters.add(effectiveBusinessDate);
            parameters.add(createdAt);
        }
        return jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    account_id, flow_type, amount, reason, created_by,
                    corporate_action_id, corporate_action_entitlement_id,
                    effective_business_date, created_at
                ) values %s
                """.formatted(values),
                parameters.toArray()
        );
    }

    public int withdrawCashForSubscriptionChunk(
            List<CapitalIncreaseSubscriptionDecision> decisions,
            LocalDateTime updatedAt
    ) {
        if (decisions.isEmpty()) {
            return 0;
        }
        requireUniqueSubscriptionAccounts(decisions);
        String cashCases = String.join(" ", Collections.nCopies(decisions.size(), "when ? then ?"));
        String accountPlaceholders = String.join(",", Collections.nCopies(decisions.size(), "?"));
        List<Object> parameters = new ArrayList<>(decisions.size() * 3 + 1);
        for (CapitalIncreaseSubscriptionDecision decision : decisions) {
            parameters.add(decision.accountId());
            parameters.add(decision.cashAmount());
        }
        parameters.add(updatedAt);
        decisions.forEach(decision -> parameters.add(decision.accountId()));
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance - case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                   and status = 'ACTIVE'
                """.formatted(cashCases, accountPlaceholders),
                parameters.toArray()
        );
    }

    public int recordCapitalIncreaseSubscriptionCashFlowChunk(
            long actionId,
            List<CapitalIncreaseSubscriptionDecision> decisions,
            LocalDate effectiveBusinessDate,
            LocalDateTime createdAt
    ) {
        if (decisions.isEmpty()) {
            return 0;
        }
        requireUniqueSubscriptionAccounts(decisions);
        String values = String.join(",", Collections.nCopies(
                decisions.size(),
                "(?, 'WITHDRAW', ?, 'CAPITAL_INCREASE_SUBSCRIPTION', 'CORPORATE_ACTION_AUTO', ?, "
                        + "(select id from stock_corporate_action_entitlement where action_id = ? and account_id = ?), ?, ?)"
        ));
        List<Object> parameters = new ArrayList<>(decisions.size() * 7);
        for (CapitalIncreaseSubscriptionDecision decision : decisions) {
            parameters.add(decision.accountId());
            parameters.add(decision.cashAmount());
            parameters.add(actionId);
            parameters.add(actionId);
            parameters.add(decision.accountId());
            parameters.add(effectiveBusinessDate);
            parameters.add(createdAt);
        }
        return jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    account_id, flow_type, amount, reason, created_by,
                    corporate_action_id, corporate_action_entitlement_id,
                    effective_business_date, created_at
                ) values %s
                """.formatted(values),
                parameters.toArray()
        );
    }

    public int subscribeAllocatedRightsChunk(
            long actionId,
            List<CapitalIncreaseSubscriptionDecision> decisions,
            String subscribedStatus,
            String sourceStatus,
            LocalDateTime subscribedAt
    ) {
        if (decisions.isEmpty()) {
            return 0;
        }
        requireUniqueSubscriptionAccounts(decisions);
        if (decisions.stream().anyMatch(decision -> decision.entitlementId() == null)) {
            throw new IllegalArgumentException("Shareholder subscription decision is missing entitlement id");
        }
        String statusCases = String.join(" ", Collections.nCopies(
                decisions.size(),
                "when ? then case when coalesce(subscribed_share_quantity, 0) + ? = share_quantity then ? else 'PARTIALLY_SUBSCRIBED' end"
        ));
        String shareCases = String.join(" ", Collections.nCopies(decisions.size(), "when ? then ?"));
        String cashCases = String.join(" ", Collections.nCopies(decisions.size(), "when ? then ?"));
        String entitlementPlaceholders = String.join(",", Collections.nCopies(decisions.size(), "?"));
        List<Object> parameters = new ArrayList<>(decisions.size() * 8 + 3);
        for (CapitalIncreaseSubscriptionDecision decision : decisions) {
            parameters.add(decision.entitlementId());
            parameters.add(decision.shareQuantity());
            parameters.add(subscribedStatus);
        }
        for (CapitalIncreaseSubscriptionDecision decision : decisions) {
            parameters.add(decision.entitlementId());
            parameters.add(decision.shareQuantity());
        }
        for (CapitalIncreaseSubscriptionDecision decision : decisions) {
            parameters.add(decision.entitlementId());
            parameters.add(decision.cashAmount());
        }
        parameters.add(subscribedAt);
        parameters.add(actionId);
        decisions.forEach(decision -> parameters.add(decision.entitlementId()));
        parameters.add(sourceStatus);
        return jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = case id %s else status end,
                       subscribed_share_quantity = coalesce(subscribed_share_quantity, 0) + case id %s else 0 end,
                       subscribed_cash_amount = coalesce(subscribed_cash_amount, 0) + case id %s else 0 end,
                       subscribed_at = ?
                 where action_id = ?
                   and id in (%s)
                   and status = ?
                """.formatted(statusCases, shareCases, cashCases, entitlementPlaceholders),
                parameters.toArray()
        );
    }

    public int createPublicOfferingSubscriptionChunk(
            long actionId,
            String symbol,
            List<CapitalIncreaseSubscriptionDecision> decisions,
            String subscribedStatus,
            LocalDateTime subscribedAt
    ) {
        if (decisions.isEmpty()) {
            return 0;
        }
        requireUniqueSubscriptionAccounts(decisions);
        if (decisions.stream().anyMatch(decision -> decision.entitlementId() != null)) {
            throw new IllegalArgumentException("Public offering subscription must not reference an entitlement id");
        }
        String values = String.join(",", Collections.nCopies(
                decisions.size(),
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, null)"
        ));
        List<Object> parameters = new ArrayList<>(decisions.size() * 12);
        for (CapitalIncreaseSubscriptionDecision decision : decisions) {
            parameters.add(actionId);
            parameters.add(decision.accountId());
            parameters.add(symbol);
            parameters.add(decision.shareQuantity());
            parameters.add(decision.shareQuantity());
            parameters.add(decision.cashAmount());
            parameters.add(decision.shareQuantity());
            parameters.add(decision.cashAmount());
            parameters.add(subscribedStatus);
            parameters.add(subscribedAt);
            parameters.add(subscribedAt);
        }
        return jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                  subscribed_share_quantity, subscribed_cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                )
                values %s
                """.formatted(values),
                parameters.toArray()
        );
    }

    public int markDividendEntitlementChunkPaid(
            List<DividendEntitlementRow> entitlements,
            String paidStatus,
            String sourceStatus,
            LocalDateTime paidAt
    ) {
        if (entitlements.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(entitlements.size(), "?"));
        List<Object> parameters = new ArrayList<>(entitlements.size() + 3);
        parameters.add(paidStatus);
        parameters.add(paidAt);
        entitlements.forEach(entitlement -> parameters.add(entitlement.id()));
        parameters.add(sourceStatus);
        return jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       paid_at = ?
                 where id in (%s)
                   and status = ?
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public int markShareEntitlementChunkPaid(
            List<ShareEntitlementRow> entitlements,
            String paidStatus,
            String sourceStatus,
            LocalDateTime paidAt
    ) {
        if (entitlements.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(entitlements.size(), "?"));
        List<Object> parameters = new ArrayList<>(entitlements.size() + 3);
        parameters.add(paidStatus);
        parameters.add(paidAt);
        entitlements.forEach(entitlement -> parameters.add(entitlement.id()));
        parameters.add(sourceStatus);
        return jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       paid_at = ?
                 where id in (%s)
                   and status = ?
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public int expireEntitlementChunk(
            List<Long> entitlementIds,
            String expiredStatus,
            String sourceStatus,
            LocalDateTime updatedAt
    ) {
        if (entitlementIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(entitlementIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(entitlementIds.size() + 3);
        parameters.add(expiredStatus);
        parameters.add(updatedAt);
        parameters.addAll(entitlementIds);
        parameters.add(sourceStatus);
        return jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       forfeited_share_quantity = case
                           when share_quantity is null then forfeited_share_quantity
                           else greatest(0, share_quantity - coalesce(subscribed_share_quantity, 0))
                       end,
                       paid_at = ?
                 where id in (%s)
                   and status = ?
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public int finalizePartiallySubscribedEntitlementChunk(
            List<Long> entitlementIds,
            String finalizedStatus,
            String partialStatus
    ) {
        if (entitlementIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(entitlementIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(entitlementIds.size() + 2);
        parameters.add(finalizedStatus);
        parameters.addAll(entitlementIds);
        parameters.add(partialStatus);
        return jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       forfeited_share_quantity = greatest(
                           0,
                           share_quantity - coalesce(subscribed_share_quantity, 0)
                       )
                 where id in (%s)
                   and status = ?
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public int createDividendEntitlementChunk(
            ExRightsActionRow row,
            long holdingSnapshotRunId,
            String announcedStatus,
            LocalDateTime createdAt,
            int limit
    ) {
        return jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                )
                select ?, account_id, symbol, quantity, null, quantity * ?, ?, ?, ?, null
                  from stock_holding_snapshot
                 where symbol = ?
                   and close_run_id = ?
                   and quantity > 0
                   and not exists (
                       select 1
                         from stock_corporate_action_entitlement entitlement
                        where entitlement.action_id = ?
                          and entitlement.account_id = stock_holding_snapshot.account_id
                   )
                 order by account_id asc
                 limit ?
                """,
                row.id(),
                row.dividendAmount(),
                announcedStatus,
                holdingSnapshotRunId,
                createdAt,
                row.symbol(),
                holdingSnapshotRunId,
                row.id(),
                limit
        );
    }

    public int createShareEntitlementChunk(
            ExRightsActionRow row,
            long holdingSnapshotRunId,
            String announcedStatus,
            LocalDateTime createdAt,
            int limit
    ) {
        return jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                )
                select ?, h.account_id, h.symbol, h.quantity,
                       floor(h.quantity * a.share_quantity / i.issued_shares),
                       null, ?, ?, ?, null
                  from stock_holding_snapshot h
                  join stock_corporate_action a on a.id = ?
                  join stock_order_book_instrument i on i.symbol = h.symbol
                 where h.symbol = ?
                   and h.close_run_id = ?
                   and h.quantity > 0
                   and floor(h.quantity * a.share_quantity / i.issued_shares) > 0
                   and not exists (
                       select 1
                         from stock_corporate_action_entitlement entitlement
                        where entitlement.action_id = ?
                          and entitlement.account_id = h.account_id
                   )
                 order by h.account_id asc
                 limit ?
                """,
                row.id(),
                announcedStatus,
                holdingSnapshotRunId,
                createdAt,
                row.id(),
                row.symbol(),
                holdingSnapshotRunId,
                row.id(),
                limit
        );
    }

    public int createPaidInRightsEntitlementChunk(
            ExRightsActionRow row,
            long holdingSnapshotRunId,
            String announcedStatus,
            LocalDateTime createdAt,
            int limit
    ) {
        return jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                )
                select ?, h.account_id, h.symbol, h.quantity,
                       floor(h.quantity * ? / i.issued_shares),
                       floor(h.quantity * ? / i.issued_shares) * ?,
                       ?, ?, ?, null
                  from stock_holding_snapshot h
                  join stock_order_book_instrument i on i.symbol = h.symbol
                 where h.symbol = ?
                   and h.close_run_id = ?
                   and h.quantity > 0
                   and floor(h.quantity * ? / i.issued_shares) > 0
                   and not exists (
                       select 1
                         from stock_corporate_action_entitlement entitlement
                        where entitlement.action_id = ?
                          and entitlement.account_id = h.account_id
                   )
                 order by h.account_id asc
                 limit ?
                """,
                row.id(),
                row.shareQuantity(),
                row.shareQuantity(),
                row.issuePrice(),
                announcedStatus,
                holdingSnapshotRunId,
                createdAt,
                row.symbol(),
                holdingSnapshotRunId,
                row.shareQuantity(),
                row.id(),
                limit
        );
    }

    public void creditPaidInSubscribedShareHolding(ShareEntitlementRow entitlement, LocalDateTime updatedAt) {
        BigDecimal cashAmount = entitlement.cashAmount();
        if (cashAmount == null || cashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Paid-in subscription cash amount is missing: " + entitlement.id());
        }
        int updatedRows = jdbcTemplate.update(
                """
                update stock_holding
                   set average_price = ((average_price * quantity) + ?) / (quantity + ?),
                       quantity = quantity + ?,
                       updated_at = ?
                 where account_id = ?
                   and symbol = ?
                """,
                cashAmount,
                entitlement.shareQuantity(),
                entitlement.shareQuantity(),
                updatedAt,
                entitlement.accountId(),
                entitlement.symbol()
        );
        if (updatedRows == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                    values (?, ?, ?, 0, ?, ?)
                    """,
                    entitlement.accountId(),
                    entitlement.symbol(),
                    entitlement.shareQuantity(),
                    cashAmount.divide(BigDecimal.valueOf(entitlement.shareQuantity()), 2, RoundingMode.HALF_UP),
                    updatedAt
            );
        }
    }

    public int lockShareHoldingsForUpdate(List<ShareEntitlementRow> entitlements) {
        if (entitlements.isEmpty()) {
            return 0;
        }
        String symbol = requireSingleSymbol(entitlements);
        List<Long> accountIds = entitlements.stream()
                .map(ShareEntitlementRow::accountId)
                .distinct()
                .sorted()
                .toList();
        if (accountIds.size() != entitlements.size()) {
            throw new IllegalArgumentException("Share entitlement chunk contains duplicate account ids");
        }
        String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(accountIds.size() + 1);
        parameters.add(symbol);
        parameters.addAll(accountIds);
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

    public int creditShareHoldingChunk(
            List<ShareEntitlementRow> entitlements,
            boolean paidInSubscription,
            LocalDateTime updatedAt
    ) {
        if (entitlements.isEmpty()) {
            return 0;
        }
        return paidInSubscription
                ? upsertPaidInShareHoldingChunk(entitlements, updatedAt)
                : updateBonusShareHoldingChunk(entitlements, updatedAt);
    }

    private int updateBonusShareHoldingChunk(
            List<ShareEntitlementRow> entitlements,
            LocalDateTime updatedAt
    ) {
        String symbol = requireSingleSymbol(entitlements);
        String quantityCases = String.join(" ", Collections.nCopies(entitlements.size(), "when ? then ?"));
        String accountPlaceholders = String.join(",", Collections.nCopies(entitlements.size(), "?"));
        List<Object> parameters = new ArrayList<>(entitlements.size() * 6 + 2);
        for (ShareEntitlementRow entitlement : entitlements) {
            parameters.add(entitlement.accountId());
            parameters.add(entitlement.shareQuantity());
        }
        for (ShareEntitlementRow entitlement : entitlements) {
            parameters.add(entitlement.accountId());
            parameters.add(entitlement.shareQuantity());
        }
        parameters.add(updatedAt);
        parameters.add(symbol);
        entitlements.forEach(entitlement -> parameters.add(entitlement.accountId()));
        return jdbcTemplate.update(
                """
                update stock_holding
                   set average_price = (average_price * quantity)
                                       / (quantity + case account_id %s else 0 end),
                       quantity = quantity + case account_id %s else 0 end,
                       updated_at = ?
                 where symbol = ?
                   and account_id in (%s)
                """.formatted(quantityCases, quantityCases, accountPlaceholders),
                parameters.toArray()
        );
    }

    private int upsertPaidInShareHoldingChunk(
            List<ShareEntitlementRow> entitlements,
            LocalDateTime updatedAt
    ) {
        if (!mysql) {
            for (ShareEntitlementRow entitlement : entitlements) {
                creditPaidInSubscribedShareHolding(entitlement, updatedAt);
            }
            return entitlements.size();
        }
        String values = String.join(",", Collections.nCopies(entitlements.size(), "(?, ?, ?, 0, ?, ?)"));
        List<Object> parameters = new ArrayList<>(entitlements.size() * 5);
        for (ShareEntitlementRow entitlement : entitlements) {
            BigDecimal cashAmount = entitlement.cashAmount();
            if (cashAmount == null || cashAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Paid-in subscription cash amount is missing: " + entitlement.id());
            }
            parameters.add(entitlement.accountId());
            parameters.add(entitlement.symbol());
            parameters.add(entitlement.shareQuantity());
            parameters.add(cashAmount.divide(
                    BigDecimal.valueOf(entitlement.shareQuantity()),
                    2,
                    RoundingMode.HALF_UP
            ));
            parameters.add(updatedAt);
        }
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                ) values %s as incoming
                on duplicate key update
                    average_price = ((stock_holding.average_price * stock_holding.quantity)
                                     + (incoming.average_price * incoming.quantity))
                                    / (stock_holding.quantity + incoming.quantity),
                    quantity = stock_holding.quantity + incoming.quantity,
                    updated_at = incoming.updated_at
                """.formatted(values),
                parameters.toArray()
        );
        return entitlements.size();
    }

    private String requireSingleSymbol(List<ShareEntitlementRow> entitlements) {
        String symbol = entitlements.getFirst().symbol();
        boolean mismatch = entitlements.stream().anyMatch(entitlement -> !symbol.equals(entitlement.symbol()));
        if (mismatch) {
            throw new IllegalArgumentException("Share entitlement chunk contains multiple symbols");
        }
        return symbol;
    }

    private void requireUniqueSubscriptionAccounts(List<CapitalIncreaseSubscriptionDecision> decisions) {
        long distinctAccountCount = decisions.stream()
                .map(CapitalIncreaseSubscriptionDecision::accountId)
                .distinct()
                .count();
        if (distinctAccountCount != decisions.size()) {
            throw new IllegalArgumentException("Capital increase subscription chunk contains duplicate accounts");
        }
    }

    private String databaseProductName(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect corporate action database product", ex);
        }
    }
}
