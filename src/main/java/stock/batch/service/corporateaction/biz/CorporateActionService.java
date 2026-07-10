package stock.batch.service.corporateaction.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.corporateaction.model.AutoParticipantCapitalIncreaseCandidate;
import stock.batch.service.batch.corporateaction.model.AutoParticipantEventProfilePolicy;
import stock.batch.service.batch.corporateaction.model.CapitalIncreaseSubscriptionActionRow;
import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.DelistingActionRow;
import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ExRightsPriceSnapshot;
import stock.batch.service.batch.corporateaction.model.ListingActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;
import stock.batch.service.batch.corporateaction.model.StockSplitActionRow;
import stock.batch.service.batch.corporateaction.reader.CorporateActionReader;
import stock.batch.service.batch.corporateaction.writer.CorporateActionAccountWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionPriceWriter;
import stock.batch.service.batch.corporateaction.writer.CorporateActionWriter;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Service
@RequiredArgsConstructor
@Slf4j
public class CorporateActionService {

    private static final String PAID_IN_CAPITAL_INCREASE = "PAID_IN_CAPITAL_INCREASE";
    private static final String STOCK_SPLIT = "STOCK_SPLIT";
    private static final String CASH_DIVIDEND = "CASH_DIVIDEND";
    private static final String BONUS_ISSUE = "BONUS_ISSUE";
    private static final String STOCK_DIVIDEND = "STOCK_DIVIDEND";
    private static final String DELISTING = "DELISTING";
    private static final String SHAREHOLDER_ALLOCATION = "SHAREHOLDER_ALLOCATION";
    private static final String PUBLIC_OFFERING = "PUBLIC_OFFERING";
    private static final String ANNOUNCED = "ANNOUNCED";
    private static final String SUBSCRIBED = "SUBSCRIBED";
    private static final String EXPIRED = "EXPIRED";
    private static final String EX_RIGHTS_APPLIED = "EX_RIGHTS_APPLIED";
    private static final String PAID = "PAID";
    private static final String LISTED = "LISTED";
    private static final String DELISTED = "DELISTED";
    private static final String RIGHTS_PROVIDER = "corporate-action-rights";
    private static final String FREE_SHARE_PROVIDER = "corporate-action-free-share";
    private static final String DELISTING_PROVIDER = "corporate-action-delisting-zero";

    private final CorporateActionReader corporateActionReader;
    private final CorporateActionOrderBookOrderGuard orderBookOrderGuard;
    private final CorporateActionPriceWriter corporateActionPriceWriter;
    private final CorporateActionWriter corporateActionWriter;
    private final CorporateActionAccountWriter corporateActionAccountWriter;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final CorporateActionTransactionExecutor transactionExecutor;

    public int applyDueCorporateActions() {
        SimulationMarketSession session = simulationMarketSessionService.currentSession();
        if (session == SimulationMarketSession.REGULAR) {
            return 0;
        }
        LocalDate actionDate = simulationMarketSessionService.currentSimulationDate();
        LocalDate requiredCloseDate = requiredCloseDate(session, actionDate);
        if (requiredCloseDate == null || !corporateActionReader.existsCompletedMarketCloseRun(requiredCloseDate)) {
            return 0;
        }
        List<RuntimeException> failures = new ArrayList<>();
        int exRightsCount = executeStage("ex-rights", failures, () -> applyDueExRights(actionDate, failures));
        int rightsPaymentCount = executeStage(
                "capital-increase-payment",
                failures,
                () -> markDueRightsPayments(actionDate, failures)
        );
        int dividendPaymentCount = executeStage(
                "cash-dividend-payment",
                failures,
                () -> payDueCashDividends(actionDate, failures)
        );
        int autoSubscriptionCount = session == SimulationMarketSession.AFTER_CLOSE
                ? executeStage(
                        "capital-increase-auto-subscription",
                        failures,
                        () -> subscribeAutoParticipantsToCapitalIncreases(actionDate, failures)
                )
                : 0;
        int rightsListingCount = executeStage(
                "capital-increase-listing",
                failures,
                () -> listDueRightsShares(actionDate, failures)
        );
        int bonusIssueListingCount = executeStage(
                "bonus-issue-listing",
                failures,
                () -> listDueFreeShareDistributions(actionDate, BONUS_ISSUE, failures)
        );
        int stockDividendListingCount = executeStage(
                "stock-dividend-listing",
                failures,
                () -> listDueFreeShareDistributions(actionDate, STOCK_DIVIDEND, failures)
        );
        int stockSplitCount = executeStage(
                "stock-split",
                failures,
                () -> applyDueStockSplits(actionDate, failures)
        );
        int delistingCount = executeStage(
                "delisting",
                failures,
                () -> applyDueDelistings(actionDate, failures)
        );
        int processedCount = autoSubscriptionCount + exRightsCount + rightsPaymentCount + dividendPaymentCount + rightsListingCount
                + bonusIssueListingCount + stockDividendListingCount + stockSplitCount
                + delistingCount;
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    private LocalDate requiredCloseDate(SimulationMarketSession session, LocalDate actionDate) {
        if (session == SimulationMarketSession.AFTER_CLOSE) {
            return actionDate;
        }
        LocalDate previousDate = actionDate.minusDays(1);
        if (previousDate.isBefore(simulationMarketSessionService.baseSimulationDate())) {
            return null;
        }
        return previousDate;
    }

    private int applyDueExRights(LocalDate today, List<RuntimeException> failures) {
        List<ExRightsActionRow> rows = corporateActionReader.findDueExRights(
                today,
                ANNOUNCED,
                List.of(PAID_IN_CAPITAL_INCREASE, CASH_DIVIDEND, BONUS_ISSUE, STOCK_DIVIDEND)
        );
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, ExRightsActionRow::symbol);

        int processed = 0;
        for (ExRightsActionRow row : rows) {
            if (symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeActionInTransaction(
                    "ex-rights",
                    row.id(),
                    failures,
                    () -> applyDueExRights(row, today)
            );
        }
        return processed;
    }

    private int applyDueExRights(ExRightsActionRow row, LocalDate today) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, row.actionType(), ANNOUNCED, "ex_rights_date")) {
            return 0;
        }
        if (!orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(row), ExRightsActionRow::symbol).isEmpty()) {
            return 0;
        }
        Long holdingSnapshotRunId = resolveRequiredHoldingSnapshotRunId(row);
        if (holdingSnapshotRunId == null && requiresHoldingSnapshot(row)) {
            return 0;
        }
        ExRightsPriceAdjustment priceAdjustment = resolveExRightsPriceAdjustment(row, holdingSnapshotRunId);
        if (adjustsExRightsPrice(row.actionType()) && priceAdjustment == null) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int updatedAction = priceAdjustment == null
                ? corporateActionWriter.markActionExRightsApplied(row.id(), EX_RIGHTS_APPLIED, ANNOUNCED, now)
                : corporateActionWriter.markActionExRightsAppliedWithPrices(
                        row.id(),
                        EX_RIGHTS_APPLIED,
                        ANNOUNCED,
                        priceAdjustment.basePrice(),
                        priceAdjustment.theoreticalExRightsPrice(),
                        now
                );
        if (updatedAction == 0) {
            return 0;
        }
        if (priceAdjustment != null) {
            String provider = resolveExRightsProvider(row.actionType());
            corporateActionPriceWriter.upsertPrice(
                    row.symbol(),
                    priceAdjustment.theoreticalExRightsPrice(),
                    provider,
                    now
            );
            corporateActionPriceWriter.insertPriceTick(
                    row.symbol(),
                    priceAdjustment.theoreticalExRightsPrice(),
                    provider,
                    now
            );
        }
        if (CASH_DIVIDEND.equals(row.actionType())) {
            createDividendEntitlements(row, holdingSnapshotRunId, now);
        }
        if (PAID_IN_CAPITAL_INCREASE.equals(row.actionType()) && holdingSnapshotRunId != null) {
            createPaidInRightsEntitlements(row, holdingSnapshotRunId, now);
        }
        if (BONUS_ISSUE.equals(row.actionType()) || STOCK_DIVIDEND.equals(row.actionType())) {
            createShareEntitlements(row, holdingSnapshotRunId, now);
        }
        return updatedAction;
    }

    private ExRightsPriceAdjustment resolveExRightsPriceAdjustment(
            ExRightsActionRow row,
            Long holdingSnapshotRunId
    ) {
        if (!adjustsExRightsPrice(row.actionType()) || holdingSnapshotRunId == null) {
            return null;
        }
        return corporateActionReader.findExRightsPriceSnapshot(holdingSnapshotRunId, row.symbol())
                .map(snapshot -> new ExRightsPriceAdjustment(
                        snapshot.closePrice(),
                        calculateTheoreticalExRightsPrice(row, snapshot)
                ))
                .orElse(null);
    }

    private BigDecimal calculateTheoreticalExRightsPrice(
            ExRightsActionRow row,
            ExRightsPriceSnapshot snapshot
    ) {
        boolean paidInCapitalIncrease = PAID_IN_CAPITAL_INCREASE.equals(row.actionType());
        BigDecimal issuePrice = paidInCapitalIncrease
                ? row.issuePrice()
                : BigDecimal.ZERO;
        if (issuePrice == null) {
            throw new IllegalStateException("Paid-in capital increase issue price is missing: " + row.id());
        }
        if (paidInCapitalIncrease && snapshot.closePrice().compareTo(issuePrice) <= 0) {
            return snapshot.closePrice();
        }
        BigDecimal existingValue = snapshot.closePrice().multiply(BigDecimal.valueOf(snapshot.issuedShares()));
        BigDecimal issueValue = issuePrice.multiply(BigDecimal.valueOf(row.shareQuantity()));
        return existingValue.add(issueValue)
                .divide(
                        BigDecimal.valueOf(snapshot.issuedShares() + row.shareQuantity()),
                        0,
                        RoundingMode.DOWN
                );
    }

    private Long resolveRequiredHoldingSnapshotRunId(ExRightsActionRow row) {
        if (!requiresHoldingSnapshot(row)) {
            return null;
        }
        return corporateActionReader.findLatestCompletedMarketCloseRunIdBefore(row.symbol(), row.exRightsDate()).orElse(null);
    }

    private boolean requiresHoldingSnapshot(String actionType) {
        return CASH_DIVIDEND.equals(actionType)
                || BONUS_ISSUE.equals(actionType)
                || STOCK_DIVIDEND.equals(actionType);
    }

    private boolean requiresHoldingSnapshot(ExRightsActionRow row) {
        return requiresHoldingSnapshot(row.actionType())
                || (PAID_IN_CAPITAL_INCREASE.equals(row.actionType())
                && SHAREHOLDER_ALLOCATION.equals(row.offeringType()));
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

    private int markDueRightsPayments(LocalDate today, List<RuntimeException> failures) {
        List<CapitalIncreaseSubscriptionActionRow> rows = corporateActionReader.findDueCapitalIncreasePayments(
                today,
                PAID_IN_CAPITAL_INCREASE,
                EX_RIGHTS_APPLIED,
                ANNOUNCED
        );
        int processed = 0;
        for (CapitalIncreaseSubscriptionActionRow row : rows) {
            processed += executeActionInTransaction(
                    "capital-increase-payment",
                    row.id(),
                    failures,
                    () -> markDueRightsPayment(row, today)
            );
        }
        return processed;
    }

    private int markDueRightsPayment(CapitalIncreaseSubscriptionActionRow row, LocalDate today) {
        String sourceStatus = SHAREHOLDER_ALLOCATION.equals(row.offeringType())
                ? EX_RIGHTS_APPLIED
                : ANNOUNCED;
        CapitalIncreaseSubscriptionActionRow lockedRow = corporateActionReader
                .findDueCapitalIncreasePaymentForUpdate(
                        row.id(),
                        today,
                        PAID_IN_CAPITAL_INCREASE,
                        sourceStatus,
                        row.offeringType()
                )
                .orElse(null);
        if (lockedRow == null) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int updatedAction = corporateActionWriter.markActionPaid(row.id(), PAID, sourceStatus, now);
        if (updatedAction > 0 && SHAREHOLDER_ALLOCATION.equals(row.offeringType())) {
            corporateActionAccountWriter.expireEntitlements(row.id(), EXPIRED, ANNOUNCED, now);
        }
        return updatedAction;
    }

    private int subscribeAutoParticipantsToCapitalIncreases(
            LocalDate today,
            List<RuntimeException> failures
    ) {
        List<CapitalIncreaseSubscriptionActionRow> rows = corporateActionReader.findOpenCapitalIncreaseSubscriptions(
                today,
                PAID_IN_CAPITAL_INCREASE,
                List.of(ANNOUNCED, EX_RIGHTS_APPLIED)
        );
        if (rows.isEmpty()) {
            return 0;
        }
        Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies = loadEventProfilePolicies();
        int processed = 0;
        for (CapitalIncreaseSubscriptionActionRow row : rows) {
            if (SHAREHOLDER_ALLOCATION.equals(row.offeringType())) {
                processed += executeActionInTransaction(
                        "shareholder-allocation-auto-subscription",
                        row.id(),
                        failures,
                        () -> subscribeShareholderAllocation(row.id(), today, policies)
                );
            }
            if (PUBLIC_OFFERING.equals(row.offeringType())) {
                processed += executeActionInTransaction(
                        "public-offering-auto-subscription",
                        row.id(),
                        failures,
                        () -> subscribePublicOffering(row.id(), today, policies)
                );
            }
        }
        return processed;
    }

    private int subscribeShareholderAllocation(
            long actionId,
            LocalDate today,
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies
    ) {
        CapitalIncreaseSubscriptionActionRow row = corporateActionReader
                .findCapitalIncreaseSubscriptionForUpdate(
                        actionId,
                        today,
                        PAID_IN_CAPITAL_INCREASE,
                        EX_RIGHTS_APPLIED,
                        SHAREHOLDER_ALLOCATION
                )
                .orElse(null);
        if (row == null) {
            return 0;
        }
        int processed = 0;
        List<AutoParticipantCapitalIncreaseCandidate> candidates =
                corporateActionReader.findShareholderAllocationAutoCandidates(row.id(), ANNOUNCED);
        for (AutoParticipantCapitalIncreaseCandidate candidate : candidates) {
            if (candidate.entitlementId() == null) {
                continue;
            }
            BigDecimal cashBalance = corporateActionReader
                    .findActiveAccountCashForUpdate(candidate.accountId())
                    .orElse(null);
            if (cashBalance == null) {
                continue;
            }
            Long availableShareQuantity = corporateActionReader
                    .findAnnouncedEntitlementShareQuantityForUpdate(
                            candidate.entitlementId(),
                            row.id(),
                            ANNOUNCED
                    )
                    .orElse(null);
            if (availableShareQuantity == null) {
                continue;
            }
            AutoParticipantEventProfilePolicy policy = eventPolicy(policies, candidate.profileType());
            long shareQuantity = desiredShareQuantity(
                    availableShareQuantity,
                    row.issuePrice(),
                    cashBalance,
                    policy.shareholderSubscriptionRate(),
                    policy.maxCashAllocationRate()
            );
            if (shareQuantity <= 0) {
                continue;
            }
            BigDecimal cashAmount = row.issuePrice().multiply(BigDecimal.valueOf(shareQuantity));
            LocalDateTime now = currentDateTime();
            if (!corporateActionAccountWriter.withdrawCashForSubscription(candidate.accountId(), cashAmount, now)) {
                continue;
            }
            int updatedEntitlement = corporateActionAccountWriter.subscribeAllocatedRights(
                    candidate.entitlementId(),
                    shareQuantity,
                    cashAmount,
                    SUBSCRIBED,
                    ANNOUNCED,
                    now
            );
            if (updatedEntitlement == 0) {
                throw new IllegalStateException("Corporate action entitlement subscription failed: " + candidate.entitlementId());
            }
            corporateActionAccountWriter.recordCapitalIncreaseSubscriptionCashFlow(candidate.accountId(), cashAmount, now);
            processed++;
        }
        return processed;
    }

    private int subscribePublicOffering(
            long actionId,
            LocalDate today,
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies
    ) {
        CapitalIncreaseSubscriptionActionRow lockedRow = corporateActionReader
                .findCapitalIncreaseSubscriptionForUpdate(
                        actionId,
                        today,
                        PAID_IN_CAPITAL_INCREASE,
                        ANNOUNCED,
                        PUBLIC_OFFERING
                )
                .orElse(null);
        if (lockedRow == null) {
            return 0;
        }
        long remainingShares = lockedRow.shareQuantity() - corporateActionReader.sumSubscribedShareQuantity(lockedRow.id());
        if (remainingShares <= 0) {
            return 0;
        }
        int processed = 0;
        List<AutoParticipantCapitalIncreaseCandidate> candidates = corporateActionReader.findPublicOfferingAutoCandidates(lockedRow.id());
        for (AutoParticipantCapitalIncreaseCandidate candidate : candidates) {
            if (remainingShares <= 0) {
                break;
            }
            BigDecimal cashBalance = corporateActionReader
                    .findActiveAccountCashForUpdate(candidate.accountId())
                    .orElse(null);
            if (cashBalance == null) {
                continue;
            }
            AutoParticipantEventProfilePolicy policy = eventPolicy(policies, candidate.profileType());
            long desiredShareQuantity = desiredShareQuantity(
                    lockedRow.shareQuantity(),
                    lockedRow.issuePrice(),
                    cashBalance,
                    policy.publicOfferingSubscriptionRate(),
                    policy.maxCashAllocationRate()
            );
            long shareQuantity = Math.min(remainingShares, desiredShareQuantity);
            if (shareQuantity <= 0) {
                continue;
            }
            BigDecimal cashAmount = lockedRow.issuePrice().multiply(BigDecimal.valueOf(shareQuantity));
            LocalDateTime now = currentDateTime();
            if (!corporateActionAccountWriter.withdrawCashForSubscription(candidate.accountId(), cashAmount, now)) {
                continue;
            }
            int insertedEntitlement = corporateActionAccountWriter.createPublicOfferingSubscription(
                    lockedRow.id(),
                    candidate.accountId(),
                    lockedRow.symbol(),
                    shareQuantity,
                    cashAmount,
                    SUBSCRIBED,
                    now
            );
            if (insertedEntitlement == 0) {
                throw new IllegalStateException("Corporate action public offering subscription failed: " + lockedRow.id() + "/" + candidate.accountId());
            }
            corporateActionAccountWriter.recordCapitalIncreaseSubscriptionCashFlow(candidate.accountId(), cashAmount, now);
            remainingShares -= shareQuantity;
            processed++;
        }
        return processed;
    }

    private long desiredShareQuantity(
            long availableShareQuantity,
            BigDecimal issuePrice,
            BigDecimal cashBalance,
            BigDecimal subscriptionRate,
            BigDecimal maxCashAllocationRate
    ) {
        if (availableShareQuantity <= 0
                || issuePrice == null
                || issuePrice.compareTo(BigDecimal.ZERO) <= 0
                || cashBalance == null
                || cashBalance.compareTo(issuePrice) < 0
                || subscriptionRate == null
                || subscriptionRate.compareTo(BigDecimal.ZERO) <= 0
                || maxCashAllocationRate == null
                || maxCashAllocationRate.compareTo(BigDecimal.ZERO) <= 0) {
            return 0L;
        }
        long policyShares = BigDecimal.valueOf(availableShareQuantity)
                .multiply(subscriptionRate)
                .setScale(0, RoundingMode.FLOOR)
                .longValue();
        BigDecimal cashLimit = cashBalance.multiply(maxCashAllocationRate);
        long cashLimitedShares = cashLimit.divide(issuePrice, 0, RoundingMode.FLOOR).longValue();
        return Math.min(availableShareQuantity, Math.min(policyShares, cashLimitedShares));
    }

    private Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> loadEventProfilePolicies() {
        Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies = defaultEventProfilePolicies();
        for (AutoParticipantEventProfilePolicy savedPolicy : corporateActionReader.findEventProfilePolicies()) {
            policies.put(savedPolicy.profileType(), savedPolicy);
        }
        return policies;
    }

    private AutoParticipantEventProfilePolicy eventPolicy(
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies,
            AutoParticipantProfileType profileType
    ) {
        return policies.getOrDefault(profileType, policies.get(AutoParticipantProfileType.defaultType()));
    }

    private Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> defaultEventProfilePolicies() {
        Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoParticipantProfileType profileType : AutoParticipantProfileType.values()) {
            policies.put(profileType, eventPolicy(profileType, "0.45", "0.20", "0.20"));
        }
        policies.put(AutoParticipantProfileType.LONG_TERM_HOLDER, eventPolicy(AutoParticipantProfileType.LONG_TERM_HOLDER, "0.95", "0.35", "0.40"));
        policies.put(AutoParticipantProfileType.DIVIDEND_REINVESTOR, eventPolicy(AutoParticipantProfileType.DIVIDEND_REINVESTOR, "0.90", "0.40", "0.35"));
        policies.put(AutoParticipantProfileType.VALUE_ANCHOR, eventPolicy(AutoParticipantProfileType.VALUE_ANCHOR, "0.85", "0.30", "0.35"));
        policies.put(AutoParticipantProfileType.PAYDAY_ACCUMULATOR, eventPolicy(AutoParticipantProfileType.PAYDAY_ACCUMULATOR, "0.75", "0.25", "0.30"));
        policies.put(AutoParticipantProfileType.WHALE, eventPolicy(AutoParticipantProfileType.WHALE, "0.90", "0.55", "0.50"));
        policies.put(AutoParticipantProfileType.CASH_DEFENSIVE, eventPolicy(AutoParticipantProfileType.CASH_DEFENSIVE, "0.25", "0.08", "0.10"));
        policies.put(AutoParticipantProfileType.OBSERVER, eventPolicy(AutoParticipantProfileType.OBSERVER, "0.05", "0.00", "0.05"));
        policies.put(AutoParticipantProfileType.MARKET_MAKER, eventPolicy(AutoParticipantProfileType.MARKET_MAKER, "0.20", "0.05", "0.10"));
        policies.put(AutoParticipantProfileType.SCALPER, eventPolicy(AutoParticipantProfileType.SCALPER, "0.15", "0.05", "0.08"));
        policies.put(AutoParticipantProfileType.DAY_TRADER, eventPolicy(AutoParticipantProfileType.DAY_TRADER, "0.20", "0.08", "0.10"));
        policies.put(AutoParticipantProfileType.PANIC_SELLER, eventPolicy(AutoParticipantProfileType.PANIC_SELLER, "0.10", "0.03", "0.05"));
        return policies;
    }

    private AutoParticipantEventProfilePolicy eventPolicy(
            AutoParticipantProfileType profileType,
            String shareholderSubscriptionRate,
            String publicOfferingSubscriptionRate,
            String maxCashAllocationRate
    ) {
        return new AutoParticipantEventProfilePolicy(
                profileType,
                new BigDecimal(shareholderSubscriptionRate),
                new BigDecimal(publicOfferingSubscriptionRate),
                new BigDecimal(maxCashAllocationRate)
        );
    }

    private int payDueCashDividends(LocalDate today, List<RuntimeException> failures) {
        List<Long> actionIds = corporateActionReader.findDueCashDividendActionIds(today, CASH_DIVIDEND, EX_RIGHTS_APPLIED);

        int processed = 0;
        for (Long actionId : actionIds) {
            processed += executeActionInTransaction(
                    "cash-dividend-payment",
                    actionId,
                    failures,
                    () -> payDueCashDividend(actionId, today)
            );
        }
        return processed;
    }

    private int payDueCashDividend(long actionId, LocalDate today) {
        if (!corporateActionReader.lockDueActionForUpdate(
                actionId, today, CASH_DIVIDEND, EX_RIGHTS_APPLIED, "payment_date")) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        List<DividendEntitlementRow> entitlements =
                corporateActionReader.findAnnouncedDividendEntitlements(actionId, ANNOUNCED);
        for (DividendEntitlementRow entitlement : entitlements) {
            int updatedAccount = corporateActionAccountWriter.creditCash(entitlement.accountId(), entitlement.cashAmount(), now);
            if (updatedAccount == 0) {
                throw new IllegalStateException("Stock account not found for dividend entitlement: " + entitlement.accountId());
            }
            corporateActionAccountWriter.recordDividendPaymentCashFlow(entitlement.accountId(), entitlement.cashAmount(), now);
            corporateActionAccountWriter.markEntitlementPaid(entitlement.id(), PAID, ANNOUNCED, now);
        }
        return corporateActionWriter.markActionPaid(actionId, PAID, EX_RIGHTS_APPLIED, now);
    }

    private int listDueRightsShares(LocalDate today, List<RuntimeException> failures) {
        return listDueShareIssues(today, PAID_IN_CAPITAL_INCREASE, PAID, failures);
    }

    private int listDueFreeShareDistributions(
            LocalDate today,
            String actionType,
            List<RuntimeException> failures
    ) {
        List<ListingActionRow> rows = corporateActionReader.findDueListings(today, actionType, EX_RIGHTS_APPLIED);
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, ListingActionRow::symbol);

        int processed = 0;
        for (ListingActionRow row : rows) {
            if (symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeActionInTransaction(
                    "free-share-listing",
                    row.id(),
                    failures,
                    () -> listDueFreeShareDistribution(row, today, actionType)
            );
        }
        return processed;
    }

    private int listDueFreeShareDistribution(ListingActionRow row, LocalDate today, String actionType) {
        ListingActionRow lockedRow = corporateActionReader
                .findDueListingForUpdate(row.id(), today, actionType, EX_RIGHTS_APPLIED)
                .orElse(null);
        if (lockedRow == null
                || !orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(lockedRow), ListingActionRow::symbol).isEmpty()) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int updatedAction = corporateActionWriter.markActionListed(lockedRow.id(), LISTED, EX_RIGHTS_APPLIED, now);
        if (updatedAction == 0) {
            return 0;
        }
        addIssuedAndTradableSharesOrThrow(lockedRow, now, "free share distribution");
        creditShareEntitlements(lockedRow.id(), now);
        return updatedAction;
    }

    private int listDueShareIssues(
            LocalDate today,
            String actionType,
            String sourceStatus,
            List<RuntimeException> failures
    ) {
        List<ListingActionRow> rows = corporateActionReader.findDueListings(today, actionType, sourceStatus);
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, ListingActionRow::symbol);

        int processed = 0;
        for (ListingActionRow row : rows) {
            if (symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeActionInTransaction(
                    "share-issue-listing",
                    row.id(),
                    failures,
                    () -> listDueShareIssue(row, today, actionType, sourceStatus)
            );
        }
        return processed;
    }

    private int listDueShareIssue(
            ListingActionRow row,
            LocalDate today,
            String actionType,
            String sourceStatus
    ) {
        ListingActionRow lockedRow = corporateActionReader
                .findDueListingForUpdate(row.id(), today, actionType, sourceStatus)
                .orElse(null);
        if (lockedRow == null
                || !orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(lockedRow), ListingActionRow::symbol).isEmpty()) {
            return 0;
        }
        long shareQuantity = PAID_IN_CAPITAL_INCREASE.equals(actionType)
                ? corporateActionReader.sumSubscribedShareQuantity(lockedRow.id())
                : lockedRow.shareQuantity();
        ListingActionRow issuanceRow = new ListingActionRow(lockedRow.id(), lockedRow.symbol(), shareQuantity);
        LocalDateTime now = currentDateTime();
        int updatedAction = corporateActionWriter.markActionListed(lockedRow.id(), LISTED, sourceStatus, now);
        if (updatedAction == 0) {
            return 0;
        }
        if (issuanceRow.shareQuantity() > 0) {
            addIssuedAndTradableSharesOrThrow(issuanceRow, now, "corporate action");
        }
        if (PAID_IN_CAPITAL_INCREASE.equals(actionType)) {
            creditSubscribedShareEntitlements(lockedRow.id(), now);
            corporateActionAccountWriter.expireEntitlements(lockedRow.id(), EXPIRED, ANNOUNCED, now);
        }
        return updatedAction;
    }

    private int applyDueStockSplits(LocalDate today, List<RuntimeException> failures) {
        List<StockSplitActionRow> rows = corporateActionReader.findDueStockSplits(today, STOCK_SPLIT, ANNOUNCED);
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, StockSplitActionRow::symbol);

        int processed = 0;
        for (StockSplitActionRow row : rows) {
            if (row.splitTo() % row.splitFrom() != 0 || symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeActionInTransaction(
                    "stock-split",
                    row.id(),
                    failures,
                    () -> applyDueStockSplit(row, today)
            );
        }
        return processed;
    }

    private int applyDueStockSplit(StockSplitActionRow row, LocalDate today) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, STOCK_SPLIT, ANNOUNCED, "listing_date")) {
            return 0;
        }
        if (!orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(row), StockSplitActionRow::symbol).isEmpty()) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int updatedAction = corporateActionWriter.markActionListed(row.id(), LISTED, ANNOUNCED, now);
        if (updatedAction == 0) {
            return 0;
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
        return updatedAction;
    }

    private int applyDueDelistings(LocalDate today, List<RuntimeException> failures) {
        List<DelistingActionRow> rows = corporateActionReader.findDueDelistings(today, DELISTING, ANNOUNCED);

        int processed = 0;
        for (DelistingActionRow row : rows) {
            processed += executeActionInTransaction(
                    "delisting",
                    row.id(),
                    failures,
                    () -> applyDueDelisting(row, today)
            );
        }
        return processed;
    }

    private int applyDueDelisting(DelistingActionRow row, LocalDate today) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, DELISTING, ANNOUNCED, "delisting_date")) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int updatedAction = corporateActionWriter.markActionDelisted(row.id(), DELISTED, ANNOUNCED, now);
        if (updatedAction == 0) {
            return 0;
        }
        orderBookOrderGuard.cancelOpenOrderBookOrders(row.symbol(), now);
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
        return updatedAction;
    }

    private void addIssuedAndTradableSharesOrThrow(ListingActionRow row, LocalDateTime now, String actionName) {
        int updatedInstrument = corporateActionWriter.addIssuedAndTradableShares(row.symbol(), row.shareQuantity(), now);
        if (updatedInstrument == 0) {
            throw new IllegalStateException("Order book instrument not found for " + actionName + ": " + row.symbol());
        }
    }

    private void createDividendEntitlements(ExRightsActionRow row, long holdingSnapshotRunId, LocalDateTime now) {
        corporateActionAccountWriter.createDividendEntitlements(row, holdingSnapshotRunId, ANNOUNCED, now);
    }

    private void createShareEntitlements(ExRightsActionRow row, long holdingSnapshotRunId, LocalDateTime now) {
        corporateActionAccountWriter.createShareEntitlements(row, holdingSnapshotRunId, ANNOUNCED, now);
    }

    private void createPaidInRightsEntitlements(ExRightsActionRow row, long holdingSnapshotRunId, LocalDateTime now) {
        corporateActionAccountWriter.createPaidInRightsEntitlements(row, holdingSnapshotRunId, ANNOUNCED, now);
    }

    private void creditShareEntitlements(long actionId, LocalDateTime now) {
        List<ShareEntitlementRow> entitlements =
                corporateActionReader.findAnnouncedShareEntitlements(actionId, ANNOUNCED);
        for (ShareEntitlementRow entitlement : entitlements) {
            int updatedHolding = corporateActionAccountWriter.creditShareHolding(entitlement, now);
            if (updatedHolding == 0) {
                throw new IllegalStateException("Stock holding not found for share entitlement: " + entitlement.accountId());
            }
            corporateActionAccountWriter.markEntitlementPaid(entitlement.id(), PAID, ANNOUNCED, now);
        }
    }

    private void creditSubscribedShareEntitlements(long actionId, LocalDateTime now) {
        List<ShareEntitlementRow> entitlements =
                corporateActionReader.findAnnouncedShareEntitlements(actionId, SUBSCRIBED);
        for (ShareEntitlementRow entitlement : entitlements) {
            corporateActionAccountWriter.creditPaidInSubscribedShareHolding(entitlement, now);
            corporateActionAccountWriter.markEntitlementPaid(entitlement.id(), PAID, SUBSCRIBED, now);
        }
    }

    private int executeActionInTransaction(
            String operation,
            long actionId,
            List<RuntimeException> failures,
            Supplier<Integer> action
    ) {
        try {
            return transactionExecutor.execute(action);
        } catch (RuntimeException ex) {
            log.error(
                    "Corporate action unit failed and was rolled back: operation={}, actionId={}, reason={}",
                    operation,
                    actionId,
                    ex.getMessage(),
                    ex
            );
            failures.add(new IllegalStateException(
                    "Corporate action " + operation + " failed: " + actionId,
                    ex
            ));
            return 0;
        }
    }

    private int executeStage(
            String operation,
            List<RuntimeException> failures,
            Supplier<Integer> stage
    ) {
        try {
            return stage.get();
        } catch (RuntimeException ex) {
            log.error(
                    "Corporate action stage failed; later stages will continue: operation={}, reason={}",
                    operation,
                    ex.getMessage(),
                    ex
            );
            failures.add(new IllegalStateException(
                    "Corporate action stage failed: " + operation,
                    ex
            ));
            return 0;
        }
    }

    private void throwIfAnyActionFailed(List<RuntimeException> failures) {
        if (failures.isEmpty()) {
            return;
        }
        IllegalStateException aggregate = new IllegalStateException(
                "Corporate action processing failed for " + failures.size() + " action unit(s)"
        );
        failures.forEach(aggregate::addSuppressed);
        throw aggregate;
    }

    private LocalDateTime currentDateTime() {
        return simulationClockService.currentMarketDateTime();
    }

    private record ExRightsPriceAdjustment(
            BigDecimal basePrice,
            BigDecimal theoreticalExRightsPrice
    ) {
    }

}
