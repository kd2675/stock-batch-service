package stock.batch.service.corporateaction.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.DelistingActionRow;
import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;
import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ListingActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;
import stock.batch.service.batch.corporateaction.model.StockSplitActionRow;
import stock.batch.service.batch.corporateaction.reader.CorporateActionReader;
import stock.batch.service.batch.corporateaction.writer.CorporateActionPriceWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;

@Service
@RequiredArgsConstructor
public class CorporateActionService {

    private static final String PAID_IN_CAPITAL_INCREASE = "PAID_IN_CAPITAL_INCREASE";
    private static final String ADDITIONAL_ISSUE = "ADDITIONAL_ISSUE";
    private static final String STOCK_SPLIT = "STOCK_SPLIT";
    private static final String CASH_DIVIDEND = "CASH_DIVIDEND";
    private static final String BONUS_ISSUE = "BONUS_ISSUE";
    private static final String STOCK_DIVIDEND = "STOCK_DIVIDEND";
    private static final String DELISTING = "DELISTING";
    private static final String ANNOUNCED = "ANNOUNCED";
    private static final String EX_RIGHTS_APPLIED = "EX_RIGHTS_APPLIED";
    private static final String PAID = "PAID";
    private static final String LISTED = "LISTED";
    private static final String DELISTED = "DELISTED";
    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String RIGHTS_PROVIDER = "corporate-action-rights";
    private static final String FREE_SHARE_PROVIDER = "corporate-action-free-share";
    private static final String DELISTING_PROVIDER = "corporate-action-delisting-zero";

    private final CorporateActionReader corporateActionReader;
    private final CorporateActionPriceWriter corporateActionPriceWriter;
    private final CorporateActionWriter corporateActionWriter;

    @Transactional
    public int applyDueCorporateActions() {
        LocalDate today = LocalDate.now();
        int exRightsCount = applyDueExRights(today);
        int rightsPaymentCount = markDueRightsPayments(today);
        int dividendPaymentCount = payDueCashDividends(today);
        int rightsListingCount = listDueRightsShares(today);
        int bonusIssueListingCount = listDueFreeShareDistributions(today, BONUS_ISSUE);
        int stockDividendListingCount = listDueFreeShareDistributions(today, STOCK_DIVIDEND);
        int additionalIssueCount = listDueAdditionalIssues(today);
        int stockSplitCount = applyDueStockSplits(today);
        int delistingCount = applyDueDelistings(today);
        return exRightsCount + rightsPaymentCount + dividendPaymentCount + rightsListingCount
                + bonusIssueListingCount + stockDividendListingCount + additionalIssueCount + stockSplitCount
                + delistingCount;
    }

    private int applyDueExRights(LocalDate today) {
        List<ExRightsActionRow> rows = corporateActionReader.findDueExRights(
                today,
                ANNOUNCED,
                List.of(PAID_IN_CAPITAL_INCREASE, CASH_DIVIDEND, BONUS_ISSUE, STOCK_DIVIDEND)
        );

        int processed = 0;
        for (ExRightsActionRow row : rows) {
            if (corporateActionReader.hasOpenOrderBookOrders(row.symbol())) {
                continue;
            }
            Long holdingSnapshotRunId = resolveRequiredHoldingSnapshotRunId(row);
            if (holdingSnapshotRunId == null && requiresHoldingSnapshot(row.actionType())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            int updatedAction = corporateActionWriter.markActionExRightsApplied(row.id(), EX_RIGHTS_APPLIED, ANNOUNCED, now);
            if (updatedAction == 0) {
                continue;
            }
            if (adjustsExRightsPrice(row.actionType())) {
                String provider = resolveExRightsProvider(row.actionType());
                corporateActionPriceWriter.upsertPrice(row.symbol(), row.theoreticalExRightsPrice(), provider, now);
                corporateActionPriceWriter.insertPriceTick(row.symbol(), row.theoreticalExRightsPrice(), provider, now);
            }
            if (CASH_DIVIDEND.equals(row.actionType())) {
                createDividendEntitlements(row, holdingSnapshotRunId, now);
            }
            if (BONUS_ISSUE.equals(row.actionType()) || STOCK_DIVIDEND.equals(row.actionType())) {
                createShareEntitlements(row, holdingSnapshotRunId, now);
            }
            processed += updatedAction;
        }
        return processed;
    }

    private Long resolveRequiredHoldingSnapshotRunId(ExRightsActionRow row) {
        if (!requiresHoldingSnapshot(row.actionType())) {
            return null;
        }
        return corporateActionReader.findLatestCompletedMarketCloseRunId(row.symbol()).orElse(null);
    }

    private boolean requiresHoldingSnapshot(String actionType) {
        return CASH_DIVIDEND.equals(actionType)
                || BONUS_ISSUE.equals(actionType)
                || STOCK_DIVIDEND.equals(actionType);
    }

    private boolean adjustsExRightsPrice(String actionType) {
        return PAID_IN_CAPITAL_INCREASE.equals(actionType)
                || BONUS_ISSUE.equals(actionType)
                || STOCK_DIVIDEND.equals(actionType);
    }

    private String resolveExRightsProvider(String actionType) {
        if (BONUS_ISSUE.equals(actionType) || STOCK_DIVIDEND.equals(actionType)) {
            return FREE_SHARE_PROVIDER;
        }
        return RIGHTS_PROVIDER;
    }

    private int markDueRightsPayments(LocalDate today) {
        return corporateActionWriter.markDueRightsPayments(
                today,
                PAID,
                PAID_IN_CAPITAL_INCREASE,
                EX_RIGHTS_APPLIED,
                LocalDateTime.now()
        );
    }

    private int payDueCashDividends(LocalDate today) {
        List<Long> actionIds = corporateActionReader.findDueCashDividendActionIds(today, CASH_DIVIDEND, EX_RIGHTS_APPLIED);

        int processed = 0;
        for (Long actionId : actionIds) {
            LocalDateTime now = LocalDateTime.now();
            List<DividendEntitlementRow> entitlements =
                    corporateActionReader.findAnnouncedDividendEntitlements(actionId, ANNOUNCED);
            for (DividendEntitlementRow entitlement : entitlements) {
                int updatedAccount = corporateActionWriter.creditCash(entitlement.accountId(), entitlement.cashAmount(), now);
                if (updatedAccount == 0) {
                    throw new IllegalStateException("Stock account not found for dividend entitlement: " + entitlement.accountId());
                }
                corporateActionWriter.recordDividendPaymentCashFlow(entitlement.accountId(), entitlement.cashAmount(), now);
                corporateActionWriter.markEntitlementPaid(entitlement.id(), PAID, ANNOUNCED, now);
            }
            processed += corporateActionWriter.markActionPaid(actionId, PAID, EX_RIGHTS_APPLIED, now);
        }
        return processed;
    }

    private int listDueRightsShares(LocalDate today) {
        return listDueShareIssues(today, PAID_IN_CAPITAL_INCREASE, PAID);
    }

    private int listDueAdditionalIssues(LocalDate today) {
        return listDueShareIssues(today, ADDITIONAL_ISSUE, ANNOUNCED);
    }

    private int listDueFreeShareDistributions(LocalDate today, String actionType) {
        List<ListingActionRow> rows = corporateActionReader.findDueListings(today, actionType, EX_RIGHTS_APPLIED);

        int processed = 0;
        for (ListingActionRow row : rows) {
            if (corporateActionReader.hasOpenOrderBookOrders(row.symbol())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            int updatedAction = corporateActionWriter.markActionListed(row.id(), LISTED, EX_RIGHTS_APPLIED, now);
            if (updatedAction == 0) {
                continue;
            }
            int updatedInstrument = corporateActionWriter.addIssuedAndTradableShares(row.symbol(), row.shareQuantity(), now);
            if (updatedInstrument == 0) {
                throw new IllegalStateException("Order book instrument not found for free share distribution: " + row.symbol());
            }
            creditShareEntitlements(row.id(), now);
            processed += updatedAction;
        }
        return processed;
    }

    private int listDueShareIssues(LocalDate today, String actionType, String sourceStatus) {
        List<ListingActionRow> rows = corporateActionReader.findDueListings(today, actionType, sourceStatus);

        int processed = 0;
        for (ListingActionRow row : rows) {
            if (corporateActionReader.hasOpenOrderBookOrders(row.symbol())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            int updatedAction = corporateActionWriter.markActionListed(row.id(), LISTED, sourceStatus, now);
            if (updatedAction == 0) {
                continue;
            }
            int updatedInstrument = corporateActionWriter.addIssuedAndTradableShares(row.symbol(), row.shareQuantity(), now);
            if (updatedInstrument == 0) {
                throw new IllegalStateException("Order book instrument not found for corporate action: " + row.symbol());
            }
            processed += updatedAction;
        }
        return processed;
    }

    private int applyDueStockSplits(LocalDate today) {
        List<StockSplitActionRow> rows = corporateActionReader.findDueStockSplits(today, STOCK_SPLIT, ANNOUNCED);

        int processed = 0;
        for (StockSplitActionRow row : rows) {
            if (row.splitTo() % row.splitFrom() != 0 || corporateActionReader.hasOpenOrderBookOrders(row.symbol())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            int updatedAction = corporateActionWriter.markActionListed(row.id(), LISTED, ANNOUNCED, now);
            if (updatedAction == 0) {
                continue;
            }

            int multiplier = row.splitTo() / row.splitFrom();
            BigDecimal priceDivisor = BigDecimal.valueOf(multiplier);
            int updatedInstrument = corporateActionWriter.multiplyInstrumentShares(row.symbol(), multiplier, now);
            if (updatedInstrument == 0) {
                throw new IllegalStateException("Order book instrument not found for stock split: " + row.symbol());
            }
            corporateActionWriter.multiplyHoldingsForSplit(row.symbol(), multiplier, priceDivisor, now);
            corporateActionWriter.adjustPriceForSplit(row.symbol(), priceDivisor, now);
            corporateActionPriceWriter.insertCurrentPriceTick(row.symbol(), "corporate-action-split", now);
            processed += updatedAction;
        }
        return processed;
    }

    private int applyDueDelistings(LocalDate today) {
        List<DelistingActionRow> rows = corporateActionReader.findDueDelistings(today, DELISTING, ANNOUNCED);

        int processed = 0;
        for (DelistingActionRow row : rows) {
            LocalDateTime now = LocalDateTime.now();
            int updatedAction = corporateActionWriter.markActionDelisted(row.id(), DELISTED, ANNOUNCED, now);
            if (updatedAction == 0) {
                continue;
            }
            cancelOpenOrderBookOrders(row.symbol(), now);
            int updatedInstrument = corporateActionWriter.delistInstrument(row.symbol(), now);
            if (updatedInstrument == 0) {
                throw new IllegalStateException("Order book instrument not found for delisting: " + row.symbol());
            }
            corporateActionWriter.haltOrderBookMarket(row.symbol(), now);
            corporateActionWriter.disableAutoMarket(row.symbol(), now);
            corporateActionWriter.disableListingAutoAccount(row.symbol(), now);
            corporateActionWriter.disableParticipantSymbolConfigs(row.symbol(), now);
            corporateActionPriceWriter.upsertPrice(row.symbol(), BigDecimal.ZERO, DELISTING_PROVIDER, now);
            corporateActionPriceWriter.insertPriceTick(row.symbol(), BigDecimal.ZERO, DELISTING_PROVIDER, now);
            processed += updatedAction;
        }
        return processed;
    }

    private void cancelOpenOrderBookOrders(String symbol, LocalDateTime now) {
        List<DelistingOrderRow> orders = corporateActionReader.findOpenOrderBookOrdersForUpdate(symbol);
        for (DelistingOrderRow order : orders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                corporateActionWriter.creditCash(order.accountId(), order.reservedCash(), now);
            }
            if (SELL.equals(order.side()) && order.remainingQuantity() > 0) {
                corporateActionWriter.releaseReservedSellQuantity(order.accountId(), order.symbol(), order.remainingQuantity(), now);
            }
            corporateActionWriter.cancelOrder(order.id(), now);
        }
    }

    private void createDividendEntitlements(ExRightsActionRow row, long holdingSnapshotRunId, LocalDateTime now) {
        corporateActionWriter.createDividendEntitlements(row, holdingSnapshotRunId, ANNOUNCED, now);
    }

    private void createShareEntitlements(ExRightsActionRow row, long holdingSnapshotRunId, LocalDateTime now) {
        corporateActionWriter.createShareEntitlements(row, holdingSnapshotRunId, ANNOUNCED, now);
    }

    private void creditShareEntitlements(long actionId, LocalDateTime now) {
        List<ShareEntitlementRow> entitlements =
                corporateActionReader.findAnnouncedShareEntitlements(actionId, ANNOUNCED);
        for (ShareEntitlementRow entitlement : entitlements) {
            int updatedHolding = corporateActionWriter.creditShareHolding(entitlement, now);
            if (updatedHolding == 0) {
                throw new IllegalStateException("Stock holding not found for share entitlement: " + entitlement.accountId());
            }
            corporateActionWriter.markEntitlementPaid(entitlement.id(), PAID, ANNOUNCED, now);
        }
    }

}
