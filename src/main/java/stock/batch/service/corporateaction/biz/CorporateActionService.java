package stock.batch.service.corporateaction.biz;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import stock.batch.service.batch.corporateaction.model.CapitalIncreaseSubscriptionDecision;
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
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
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
    private static final int MAX_ACCOUNT_CHUNK_SIZE = 1_000;
    private static final int MAX_ACTION_BATCH_LIMIT = 200;

    private final CorporateActionReader corporateActionReader;
    private final CorporateActionOrderBookOrderGuard orderBookOrderGuard;
    private final CorporateActionPriceWriter corporateActionPriceWriter;
    private final CorporateActionWriter corporateActionWriter;
    private final CorporateActionAccountWriter corporateActionAccountWriter;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final CorporateActionTransactionExecutor transactionExecutor;
    private final CorporateActionProcessingLedger processingLedger;
    private final MarketSessionFenceService marketSessionFenceService;

    @Value("${stock.batch.corporate-action.account-chunk-size:200}")
    private int accountChunkSize = 200;

    @Value("${stock.batch.corporate-action.action-batch-limit:25}")
    private int actionBatchLimit = 25;

    @PostConstruct
    void validateVolumeConfiguration() {
        validateAccountChunkSize(accountChunkSize);
        validateActionBatchLimit(actionBatchLimit);
    }

    public int applyDueCorporateActions() {
        SimulationMarketSession session = simulationMarketSessionService.currentSession();
        if (session == SimulationMarketSession.REGULAR) {
            return 0;
        }
        requireClosedMarket("corporate actions");
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

    /**
     * Spring Batch stage entry points intentionally keep unit failures local. Each action or
     * account chunk already commits independently with a durable processing-ledger row. The
     * final validation Step fails the Job when any due work remains, which lets unrelated
     * overnight stages continue without putting more work on the regular-session order path.
     */
    public int processCashDividendPaymentStep(LocalDate effectiveBusinessDate) {
        requireCorporateCashStage(effectiveBusinessDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "cash-dividend-payment",
                failures,
                () -> payDueCashDividends(effectiveBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int processCapitalIncreaseAutoSubscriptionStep(LocalDate effectiveBusinessDate) {
        requireCorporateCashStage(effectiveBusinessDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "capital-increase-auto-subscription",
                failures,
                () -> subscribeAutoParticipantsToCapitalIncreases(effectiveBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int processCapitalIncreasePaymentStep(LocalDate effectiveBusinessDate) {
        requireCorporateCashStage(effectiveBusinessDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "capital-increase-payment",
                failures,
                () -> markDueRightsPayments(effectiveBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int validateCorporateCashStep(LocalDate effectiveBusinessDate) {
        requireCorporateCashStage(effectiveBusinessDate);
        long incompleteCount = corporateActionReader.countIncompleteCorporateCashActions(effectiveBusinessDate);
        if (incompleteCount > 0) {
            throw new IllegalStateException(
                    "Corporate cash phase left incomplete actions: businessDate=%s, count=%d"
                            .formatted(effectiveBusinessDate, incompleteCount)
            );
        }
        return 0;
    }

    public int processExRightsStep(LocalDate preparingBusinessDate, LocalDate requiredCloseDate) {
        requirePreOpenStage(preparingBusinessDate, requiredCloseDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "ex-rights",
                failures,
                () -> applyDueExRights(preparingBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int processCapitalIncreaseListingStep(LocalDate preparingBusinessDate, LocalDate requiredCloseDate) {
        requirePreOpenStage(preparingBusinessDate, requiredCloseDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "capital-increase-listing",
                failures,
                () -> listDueRightsShares(preparingBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int processFreeShareListingStep(LocalDate preparingBusinessDate, LocalDate requiredCloseDate) {
        requirePreOpenStage(preparingBusinessDate, requiredCloseDate);
        List<RuntimeException> failures = new ArrayList<>();
        int bonusIssueCount = executeStage(
                "bonus-issue-listing",
                failures,
                () -> listDueFreeShareDistributions(preparingBusinessDate, BONUS_ISSUE, failures)
        );
        int stockDividendCount = executeStage(
                "stock-dividend-listing",
                failures,
                () -> listDueFreeShareDistributions(preparingBusinessDate, STOCK_DIVIDEND, failures)
        );
        throwIfAnyActionFailed(failures);
        return bonusIssueCount + stockDividendCount;
    }

    public int processStockSplitStep(LocalDate preparingBusinessDate, LocalDate requiredCloseDate) {
        requirePreOpenStage(preparingBusinessDate, requiredCloseDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "stock-split",
                failures,
                () -> applyDueStockSplits(preparingBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int processDelistingStep(LocalDate preparingBusinessDate, LocalDate requiredCloseDate) {
        requirePreOpenStage(preparingBusinessDate, requiredCloseDate);
        List<RuntimeException> failures = new ArrayList<>();
        int processedCount = executeStage(
                "delisting",
                failures,
                () -> applyDueDelistings(preparingBusinessDate, failures)
        );
        throwIfAnyActionFailed(failures);
        return processedCount;
    }

    public int validatePreOpenSecurityTransformsStep(
            LocalDate preparingBusinessDate,
            LocalDate requiredCloseDate
    ) {
        requirePreOpenStage(preparingBusinessDate, requiredCloseDate);
        long incompleteCount = corporateActionReader.countIncompletePreOpenSecurityTransforms(preparingBusinessDate);
        if (incompleteCount > 0) {
            throw new IllegalStateException(
                    "Pre-open corporate action phase left incomplete transforms: businessDate=%s, count=%d"
                            .formatted(preparingBusinessDate, incompleteCount)
            );
        }
        return 0;
    }

    private void requireCorporateCashStage(LocalDate effectiveBusinessDate) {
        requireClosedMarket("corporate cash actions");
        if (effectiveBusinessDate == null
                || !corporateActionReader.existsCompletedMarketCloseRun(effectiveBusinessDate)) {
            throw new IllegalStateException(
                    "Corporate cash stage requires a completed full-market close: businessDate="
                            + effectiveBusinessDate
            );
        }
    }

    private void requirePreOpenStage(LocalDate preparingBusinessDate, LocalDate requiredCloseDate) {
        requireClosedMarket("pre-open security transforms");
        if (preparingBusinessDate == null
                || requiredCloseDate == null
                || !corporateActionReader.existsCompletedMarketCloseRun(requiredCloseDate)) {
            throw new IllegalStateException(
                    "Pre-open corporate action stage requires a completed prior close: preparingBusinessDate=%s, requiredCloseDate=%s"
                            .formatted(preparingBusinessDate, requiredCloseDate)
            );
        }
    }

    private void requireClosedMarket(String operation) {
        if (marketSessionFenceService.hasOpenMarket()) {
            throw new IllegalStateException("Cannot run " + operation + " while any market is open");
        }
        marketSessionFenceService.assertMarketLedgerMutationAllowed(operation);
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
                List.of(PAID_IN_CAPITAL_INCREASE, CASH_DIVIDEND, BONUS_ISSUE, STOCK_DIVIDEND),
                normalizedActionBatchLimit()
        );
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, ExRightsActionRow::symbol);

        int processed = 0;
        for (ExRightsActionRow row : rows) {
            if (symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeChunkedAction(
                    "ex-rights",
                    row.id(),
                    failures,
                    () -> applyDueExRightsInChunks(row, today, failures)
            );
        }
        return processed;
    }

    private int applyDueExRightsInChunks(
            ExRightsActionRow row,
            LocalDate today,
            List<RuntimeException> failures
    ) {
        String operation = "ex-rights";
        if (isOperationCompleted(row.id(), operation, today)) {
            return 0;
        }
        Long holdingSnapshotRunId = resolveRequiredHoldingSnapshotRunId(row);
        if (holdingSnapshotRunId == null && requiresHoldingSnapshot(row)) {
            return 0;
        }
        int chunkSize = normalizedAccountChunkSize();
        while (holdingSnapshotRunId != null) {
            Integer insertedCount = transactionExecutor.executeValue(
                    () -> createExRightsEntitlementChunk(
                            row,
                            today,
                            holdingSnapshotRunId,
                            chunkSize
                    )
            );
            if (insertedCount == null || insertedCount == 0) {
                break;
            }
        }
        Long frozenHoldingSnapshotRunId = holdingSnapshotRunId;
        return executeActionInTransaction(
                operation,
                row.id(),
                today,
                failures,
                () -> finalizeDueExRights(row, today, frozenHoldingSnapshotRunId)
        );
    }

    private int createExRightsEntitlementChunk(
            ExRightsActionRow row,
            LocalDate today,
            long holdingSnapshotRunId,
            int chunkSize
    ) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, row.actionType(), ANNOUNCED, "ex_rights_date")) {
            return 0;
        }
        if (!orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(row), ExRightsActionRow::symbol).isEmpty()) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        if (CASH_DIVIDEND.equals(row.actionType())) {
            return corporateActionAccountWriter.createDividendEntitlementChunk(
                    row, holdingSnapshotRunId, ANNOUNCED, now, chunkSize
            );
        }
        if (PAID_IN_CAPITAL_INCREASE.equals(row.actionType())) {
            return corporateActionAccountWriter.createPaidInRightsEntitlementChunk(
                    row, holdingSnapshotRunId, ANNOUNCED, now, chunkSize
            );
        }
        if (BONUS_ISSUE.equals(row.actionType()) || STOCK_DIVIDEND.equals(row.actionType())) {
            return corporateActionAccountWriter.createShareEntitlementChunk(
                    row, holdingSnapshotRunId, ANNOUNCED, now, chunkSize
            );
        }
        return 0;
    }

    private int finalizeDueExRights(
            ExRightsActionRow row,
            LocalDate today,
            Long holdingSnapshotRunId
    ) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, row.actionType(), ANNOUNCED, "ex_rights_date")) {
            return 0;
        }
        if (!orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(row), ExRightsActionRow::symbol).isEmpty()) {
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
                ANNOUNCED,
                normalizedActionBatchLimit()
        );
        int processed = 0;
        for (CapitalIncreaseSubscriptionActionRow row : rows) {
            if (SHAREHOLDER_ALLOCATION.equals(row.offeringType())) {
                processed += executeChunkedAction(
                        "capital-increase-payment",
                        row.id(),
                        failures,
                        () -> markDueRightsPaymentInChunks(row, today, failures)
                );
            } else {
                processed += executeActionInTransaction(
                        "capital-increase-payment",
                        row.id(),
                        today,
                        failures,
                        () -> markDueRightsPayment(row, today)
                );
            }
        }
        return processed;
    }

    private int markDueRightsPaymentInChunks(
            CapitalIncreaseSubscriptionActionRow row,
            LocalDate today,
            List<RuntimeException> failures
    ) {
        String operation = "capital-increase-payment";
        if (isOperationCompleted(row.id(), operation, today)) {
            return 0;
        }
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            Integer selectedCount = transactionExecutor.executeValue(
                    () -> expireRightsEntitlementChunk(row, today, chunkSize)
            );
            if (selectedCount == null || selectedCount == 0) {
                break;
            }
        }
        return executeActionInTransaction(
                operation,
                row.id(),
                today,
                failures,
                () -> markDueRightsPayment(row, today)
        );
    }

    private int expireRightsEntitlementChunk(
            CapitalIncreaseSubscriptionActionRow row,
            LocalDate today,
            int chunkSize
    ) {
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
        List<Long> entitlementIds = corporateActionReader.findEntitlementIdChunk(
                row.id(),
                ANNOUNCED,
                chunkSize
        );
        int updated = corporateActionAccountWriter.expireEntitlementChunk(
                entitlementIds,
                EXPIRED,
                ANNOUNCED,
                currentDateTime()
        );
        if (updated != entitlementIds.size()) {
            throw new IllegalStateException("Rights entitlement expiration count mismatch: " + row.id());
        }
        return entitlementIds.size();
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
        if (SHAREHOLDER_ALLOCATION.equals(row.offeringType())
                && corporateActionReader.existsEntitlementWithStatus(row.id(), ANNOUNCED)) {
            throw new IllegalStateException("Unsubscribed rights remain before payment completion: " + row.id());
        }
        int updatedAction = corporateActionWriter.markActionPaid(row.id(), PAID, sourceStatus, now);
        return updatedAction;
    }

    private int subscribeAutoParticipantsToCapitalIncreases(
            LocalDate today,
            List<RuntimeException> failures
    ) {
        List<CapitalIncreaseSubscriptionActionRow> rows = corporateActionReader.findOpenCapitalIncreaseSubscriptions(
                today,
                PAID_IN_CAPITAL_INCREASE,
                List.of(ANNOUNCED, EX_RIGHTS_APPLIED),
                normalizedActionBatchLimit()
        );
        if (rows.isEmpty()) {
            return 0;
        }
        Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies = loadEventProfilePolicies();
        int processed = 0;
        for (CapitalIncreaseSubscriptionActionRow row : rows) {
            if (SHAREHOLDER_ALLOCATION.equals(row.offeringType())) {
                processed += executeChunkedAction(
                        "shareholder-allocation-auto-subscription",
                        row.id(),
                        failures,
                        () -> subscribeShareholderAllocationInChunks(row.id(), today, policies)
                );
            }
            if (PUBLIC_OFFERING.equals(row.offeringType())) {
                processed += executeChunkedAction(
                        "public-offering-auto-subscription",
                        row.id(),
                        failures,
                        () -> subscribePublicOfferingInChunks(row.id(), today, policies)
                );
            }
        }
        return processed;
    }

    private int subscribeShareholderAllocationInChunks(
            long actionId,
            LocalDate today,
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies
    ) {
        String operation = "shareholder-allocation-auto-subscription";
        if (isOperationCompleted(actionId, operation, today)) {
            return 0;
        }
        int processed = 0;
        long afterAccountId = 0L;
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            long cursor = afterAccountId;
            AccountChunkResult chunk = transactionExecutor.executeValue(() -> subscribeShareholderAllocationChunk(
                    actionId,
                    today,
                    operation,
                    policies,
                    cursor,
                    chunkSize
            ));
            if (chunk == null || chunk.selectedCount() == 0) {
                break;
            }
            processed += chunk.processedCount();
            afterAccountId = chunk.lastAccountId();
            if (chunk.selectedCount() < chunkSize) {
                break;
            }
        }
        completeOperation(actionId, operation, today, processed);
        return processed;
    }

    private AccountChunkResult subscribeShareholderAllocationChunk(
            long actionId,
            LocalDate today,
            String operation,
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies,
            long afterAccountId,
            int chunkSize
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
            return AccountChunkResult.empty(afterAccountId);
        }
        int processed = 0;
        List<AutoParticipantCapitalIncreaseCandidate> candidates =
                corporateActionReader.findShareholderAllocationAutoCandidateChunk(
                        row.id(),
                        ANNOUNCED,
                        operation,
                        today,
                        afterAccountId,
                        chunkSize
                );
        if (candidates.isEmpty()) {
            return AccountChunkResult.empty(afterAccountId);
        }
        Map<Long, BigDecimal> cashByAccountId = corporateActionReader.findActiveAccountCashForUpdate(
                candidates.stream().map(AutoParticipantCapitalIncreaseCandidate::accountId).toList()
        );
        Map<Long, Long> sharesByEntitlementId = corporateActionReader.findAnnouncedEntitlementShareQuantityForUpdate(
                candidates.stream()
                        .map(AutoParticipantCapitalIncreaseCandidate::entitlementId)
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                row.id(),
                ANNOUNCED
        );
        LocalDateTime now = currentDateTime();
        List<CapitalIncreaseSubscriptionDecision> decisions = new ArrayList<>();
        List<CorporateActionProcessingLedger.AccountCompletion> completions = new ArrayList<>(candidates.size());
        for (AutoParticipantCapitalIncreaseCandidate candidate : candidates) {
            if (candidate.entitlementId() == null) {
                completions.add(accountDecision(candidate.accountId(), 0, BigDecimal.ZERO, 0L, null));
                continue;
            }
            String ledgerReferenceId = "entitlement:" + candidate.entitlementId();
            BigDecimal cashBalance = cashByAccountId.get(candidate.accountId());
            if (cashBalance == null) {
                completions.add(accountDecision(
                        candidate.accountId(), 0, BigDecimal.ZERO, 0L, ledgerReferenceId
                ));
                continue;
            }
            Long availableShareQuantity = sharesByEntitlementId.get(candidate.entitlementId());
            if (availableShareQuantity == null) {
                completions.add(accountDecision(
                        candidate.accountId(), 0, BigDecimal.ZERO, 0L, ledgerReferenceId
                ));
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
                completions.add(accountDecision(
                        candidate.accountId(), 0, BigDecimal.ZERO, 0L, ledgerReferenceId
                ));
                continue;
            }
            BigDecimal cashAmount = row.issuePrice().multiply(BigDecimal.valueOf(shareQuantity));
            decisions.add(new CapitalIncreaseSubscriptionDecision(
                    candidate.entitlementId(),
                    candidate.accountId(),
                    shareQuantity,
                    cashAmount
            ));
            completions.add(accountDecision(
                    candidate.accountId(), 1, cashAmount, shareQuantity, ledgerReferenceId
            ));
            processed++;
        }
        if (!decisions.isEmpty()) {
            requireChunkCount(
                    "shareholder subscription cash withdrawal",
                    decisions.size(),
                    corporateActionAccountWriter.withdrawCashForSubscriptionChunk(decisions, now)
            );
            requireChunkCount(
                    "shareholder subscription entitlement update",
                    decisions.size(),
                    corporateActionAccountWriter.subscribeAllocatedRightsChunk(
                            row.id(),
                            decisions,
                            SUBSCRIBED,
                            ANNOUNCED,
                            now
                    )
            );
            requireChunkCount(
                    "shareholder subscription cash-flow insert",
                    decisions.size(),
                    corporateActionAccountWriter.recordCapitalIncreaseSubscriptionCashFlowChunk(decisions, now)
            );
        }
        requireChunkCount(
                "shareholder subscription processing-ledger insert",
                completions.size(),
                processingLedger.completeAccounts(row.id(), operation, today, completions, now)
        );
        return AccountChunkResult.of(candidates, processed, afterAccountId, false);
    }

    private int subscribePublicOfferingInChunks(
            long actionId,
            LocalDate today,
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies
    ) {
        String operation = "public-offering-auto-subscription";
        if (isOperationCompleted(actionId, operation, today)) {
            return 0;
        }
        int processed = 0;
        long afterAccountId = 0L;
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            long cursor = afterAccountId;
            AccountChunkResult chunk = transactionExecutor.executeValue(() -> subscribePublicOfferingChunk(
                    actionId,
                    today,
                    operation,
                    policies,
                    cursor,
                    chunkSize
            ));
            if (chunk == null || chunk.selectedCount() == 0) {
                break;
            }
            processed += chunk.processedCount();
            afterAccountId = chunk.lastAccountId();
            if (chunk.exhausted() || chunk.selectedCount() < chunkSize) {
                break;
            }
        }
        completeOperation(actionId, operation, today, processed);
        return processed;
    }

    private AccountChunkResult subscribePublicOfferingChunk(
            long actionId,
            LocalDate today,
            String operation,
            Map<AutoParticipantProfileType, AutoParticipantEventProfilePolicy> policies,
            long afterAccountId,
            int chunkSize
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
            return AccountChunkResult.empty(afterAccountId);
        }
        long remainingShares = lockedRow.shareQuantity() - corporateActionReader.sumSubscribedShareQuantity(lockedRow.id());
        if (remainingShares <= 0) {
            return AccountChunkResult.exhausted(afterAccountId);
        }
        int processed = 0;
        List<AutoParticipantCapitalIncreaseCandidate> candidates = corporateActionReader.findPublicOfferingAutoCandidateChunk(
                lockedRow.id(),
                operation,
                today,
                afterAccountId,
                chunkSize
        );
        if (candidates.isEmpty()) {
            return AccountChunkResult.empty(afterAccountId);
        }
        Map<Long, BigDecimal> cashByAccountId = corporateActionReader.findActiveAccountCashForUpdate(
                candidates.stream().map(AutoParticipantCapitalIncreaseCandidate::accountId).toList()
        );
        LocalDateTime now = currentDateTime();
        List<CapitalIncreaseSubscriptionDecision> decisions = new ArrayList<>();
        List<CorporateActionProcessingLedger.AccountCompletion> completions = new ArrayList<>(candidates.size());
        for (AutoParticipantCapitalIncreaseCandidate candidate : candidates) {
            if (remainingShares <= 0) {
                break;
            }
            BigDecimal cashBalance = cashByAccountId.get(candidate.accountId());
            if (cashBalance == null) {
                completions.add(accountDecision(candidate.accountId(), 0, BigDecimal.ZERO, 0L, null));
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
                completions.add(accountDecision(candidate.accountId(), 0, BigDecimal.ZERO, 0L, null));
                continue;
            }
            BigDecimal cashAmount = lockedRow.issuePrice().multiply(BigDecimal.valueOf(shareQuantity));
            decisions.add(new CapitalIncreaseSubscriptionDecision(
                    null,
                    candidate.accountId(),
                    shareQuantity,
                    cashAmount
            ));
            completions.add(accountDecision(
                    candidate.accountId(),
                    1,
                    cashAmount,
                    shareQuantity,
                    "account:" + candidate.accountId()
            ));
            remainingShares -= shareQuantity;
            processed++;
        }
        if (!decisions.isEmpty()) {
            requireChunkCount(
                    "public offering subscription cash withdrawal",
                    decisions.size(),
                    corporateActionAccountWriter.withdrawCashForSubscriptionChunk(decisions, now)
            );
            requireChunkCount(
                    "public offering entitlement insert",
                    decisions.size(),
                    corporateActionAccountWriter.createPublicOfferingSubscriptionChunk(
                            lockedRow.id(),
                            lockedRow.symbol(),
                            decisions,
                            SUBSCRIBED,
                            now
                    )
            );
            requireChunkCount(
                    "public offering subscription cash-flow insert",
                    decisions.size(),
                    corporateActionAccountWriter.recordCapitalIncreaseSubscriptionCashFlowChunk(decisions, now)
            );
        }
        requireChunkCount(
                "public offering subscription processing-ledger insert",
                completions.size(),
                processingLedger.completeAccounts(lockedRow.id(), operation, today, completions, now)
        );
        return AccountChunkResult.of(candidates, processed, afterAccountId, remainingShares <= 0);
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
        List<Long> actionIds = corporateActionReader.findDueCashDividendActionIds(
                today,
                CASH_DIVIDEND,
                EX_RIGHTS_APPLIED,
                normalizedActionBatchLimit()
        );

        int processed = 0;
        for (Long actionId : actionIds) {
            processed += executeChunkedAction(
                    "cash-dividend-payment",
                    actionId,
                    failures,
                    () -> payDueCashDividendInChunks(actionId, today, failures)
            );
        }
        return processed;
    }

    private int payDueCashDividendInChunks(
            long actionId,
            LocalDate today,
            List<RuntimeException> failures
    ) {
        String operation = "cash-dividend-payment";
        if (isOperationCompleted(actionId, operation, today)) {
            return 0;
        }
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            Integer selectedCount = transactionExecutor.executeValue(
                    () -> payDueCashDividendChunk(actionId, today, operation, chunkSize)
            );
            if (selectedCount == null || selectedCount == 0) {
                break;
            }
        }
        return executeActionInTransaction(
                operation,
                actionId,
                today,
                failures,
                () -> finalizeCashDividend(actionId, today)
        );
    }

    private int payDueCashDividendChunk(long actionId, LocalDate today, String operation, int chunkSize) {
        if (!corporateActionReader.lockDueActionForUpdate(
                actionId, today, CASH_DIVIDEND, EX_RIGHTS_APPLIED, "payment_date")) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        List<DividendEntitlementRow> entitlements =
                corporateActionReader.findDividendEntitlementChunk(actionId, ANNOUNCED, chunkSize);
        if (entitlements.isEmpty()) {
            return 0;
        }
        requireChunkCount(
                "dividend account lock",
                entitlements.size(),
                corporateActionAccountWriter.lockDividendAccountsForUpdate(entitlements)
        );
        requireChunkCount(
                "dividend cash credit",
                entitlements.size(),
                corporateActionAccountWriter.creditDividendCashChunk(entitlements, now)
        );
        requireChunkCount(
                "dividend cash-flow insert",
                entitlements.size(),
                corporateActionAccountWriter.recordDividendPaymentCashFlowChunk(entitlements, now)
        );
        requireChunkCount(
                "dividend entitlement completion",
                entitlements.size(),
                corporateActionAccountWriter.markDividendEntitlementChunkPaid(
                        entitlements,
                        PAID,
                        ANNOUNCED,
                        now
                )
        );
        List<CorporateActionProcessingLedger.AccountCompletion> completions = entitlements.stream()
                .map(entitlement -> new CorporateActionProcessingLedger.AccountCompletion(
                        entitlement.accountId(),
                        1,
                        entitlement.cashAmount(),
                        0L,
                        "entitlement:" + entitlement.id()
                ))
                .toList();
        requireChunkCount(
                "dividend processing-ledger insert",
                entitlements.size(),
                processingLedger.completeAccounts(actionId, operation, today, completions, now)
        );
        return entitlements.size();
    }

    private int finalizeCashDividend(long actionId, LocalDate today) {
        if (!corporateActionReader.lockDueActionForUpdate(
                actionId, today, CASH_DIVIDEND, EX_RIGHTS_APPLIED, "payment_date")) {
            return 0;
        }
        if (corporateActionReader.existsEntitlementWithStatus(actionId, ANNOUNCED)) {
            throw new IllegalStateException("Dividend entitlements remain before action completion: " + actionId);
        }
        LocalDateTime now = currentDateTime();
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
        List<ListingActionRow> rows = corporateActionReader.findDueListings(
                today,
                actionType,
                EX_RIGHTS_APPLIED,
                normalizedActionBatchLimit()
        );
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, ListingActionRow::symbol);

        int processed = 0;
        for (ListingActionRow row : rows) {
            if (symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeChunkedAction(
                    "free-share-listing",
                    row.id(),
                    failures,
                    () -> listDueFreeShareDistributionInChunks(row, today, actionType, failures)
            );
        }
        return processed;
    }

    private int listDueFreeShareDistributionInChunks(
            ListingActionRow row,
            LocalDate today,
            String actionType,
            List<RuntimeException> failures
    ) {
        String operation = "free-share-listing";
        if (isOperationCompleted(row.id(), operation, today)) {
            return 0;
        }
        processShareEntitlementChunks(
                row.id(),
                today,
                actionType,
                EX_RIGHTS_APPLIED,
                ANNOUNCED,
                operation,
                false
        );
        return executeActionInTransaction(
                operation,
                row.id(),
                today,
                failures,
                () -> finalizeFreeShareDistribution(row, today, actionType)
        );
    }

    private int finalizeFreeShareDistribution(ListingActionRow row, LocalDate today, String actionType) {
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
        return updatedAction;
    }

    private int listDueShareIssues(
            LocalDate today,
            String actionType,
            String sourceStatus,
            List<RuntimeException> failures
    ) {
        List<ListingActionRow> rows = corporateActionReader.findDueListings(
                today,
                actionType,
                sourceStatus,
                normalizedActionBatchLimit()
        );
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, ListingActionRow::symbol);

        int processed = 0;
        for (ListingActionRow row : rows) {
            if (symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeChunkedAction(
                    "share-issue-listing",
                    row.id(),
                    failures,
                    () -> listDueShareIssueInChunks(row, today, actionType, sourceStatus, failures)
            );
        }
        return processed;
    }

    private int listDueShareIssueInChunks(
            ListingActionRow row,
            LocalDate today,
            String actionType,
            String sourceStatus,
            List<RuntimeException> failures
    ) {
        String operation = "share-issue-listing";
        if (isOperationCompleted(row.id(), operation, today)) {
            return 0;
        }
        if (PAID_IN_CAPITAL_INCREASE.equals(actionType)) {
            processShareEntitlementChunks(
                    row.id(),
                    today,
                    actionType,
                    sourceStatus,
                    SUBSCRIBED,
                    operation,
                    true
            );
            expireListingEntitlementChunks(row.id(), today, actionType, sourceStatus);
        }
        return executeActionInTransaction(
                operation,
                row.id(),
                today,
                failures,
                () -> finalizeShareIssue(row, today, actionType, sourceStatus)
        );
    }

    private int finalizeShareIssue(
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
        if (PAID_IN_CAPITAL_INCREASE.equals(actionType)
                && (corporateActionReader.existsEntitlementWithStatus(lockedRow.id(), SUBSCRIBED)
                || corporateActionReader.existsEntitlementWithStatus(lockedRow.id(), ANNOUNCED))) {
            throw new IllegalStateException("Capital increase entitlements remain before listing: " + lockedRow.id());
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
        return updatedAction;
    }

    private void expireListingEntitlementChunks(
            long actionId,
            LocalDate today,
            String actionType,
            String sourceStatus
    ) {
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            Integer selectedCount = transactionExecutor.executeValue(() -> {
                ListingActionRow lockedRow = corporateActionReader
                        .findDueListingForUpdate(actionId, today, actionType, sourceStatus)
                        .orElse(null);
                if (lockedRow == null) {
                    return 0;
                }
                List<Long> entitlementIds = corporateActionReader.findEntitlementIdChunk(
                        actionId,
                        ANNOUNCED,
                        chunkSize
                );
                int updated = corporateActionAccountWriter.expireEntitlementChunk(
                        entitlementIds,
                        EXPIRED,
                        ANNOUNCED,
                        currentDateTime()
                );
                if (updated != entitlementIds.size()) {
                    throw new IllegalStateException("Listing entitlement expiration count mismatch: " + actionId);
                }
                return entitlementIds.size();
            });
            if (selectedCount == null || selectedCount == 0) {
                return;
            }
        }
    }

    private void processShareEntitlementChunks(
            long actionId,
            LocalDate today,
            String actionType,
            String sourceStatus,
            String entitlementStatus,
            String operation,
            boolean paidInSubscription
    ) {
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            Integer selectedCount = transactionExecutor.executeValue(() -> processShareEntitlementChunk(
                    actionId,
                    today,
                    actionType,
                    sourceStatus,
                    entitlementStatus,
                    operation,
                    paidInSubscription,
                    chunkSize
            ));
            if (selectedCount == null || selectedCount == 0) {
                return;
            }
        }
    }

    private int processShareEntitlementChunk(
            long actionId,
            LocalDate today,
            String actionType,
            String sourceStatus,
            String entitlementStatus,
            String operation,
            boolean paidInSubscription,
            int chunkSize
    ) {
        ListingActionRow lockedRow = corporateActionReader
                .findDueListingForUpdate(actionId, today, actionType, sourceStatus)
                .orElse(null);
        if (lockedRow == null
                || !orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(lockedRow), ListingActionRow::symbol).isEmpty()) {
            return 0;
        }
        List<ShareEntitlementRow> entitlements = corporateActionReader.findShareEntitlementChunk(
                actionId,
                entitlementStatus,
                chunkSize
        );
        if (entitlements.isEmpty()) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int lockedHoldingCount = corporateActionAccountWriter.lockShareHoldingsForUpdate(entitlements);
        if (!paidInSubscription) {
            requireChunkCount("share entitlement holding lock", entitlements.size(), lockedHoldingCount);
        }
        requireChunkCount(
                "share entitlement holding credit",
                entitlements.size(),
                corporateActionAccountWriter.creditShareHoldingChunk(
                        entitlements,
                        paidInSubscription,
                        now
                )
        );
        requireChunkCount(
                "share entitlement completion",
                entitlements.size(),
                corporateActionAccountWriter.markShareEntitlementChunkPaid(
                        entitlements,
                        PAID,
                        entitlementStatus,
                        now
                )
        );
        List<CorporateActionProcessingLedger.AccountCompletion> completions = entitlements.stream()
                .map(entitlement -> new CorporateActionProcessingLedger.AccountCompletion(
                        entitlement.accountId(),
                        1,
                        entitlement.cashAmount() == null ? BigDecimal.ZERO : entitlement.cashAmount(),
                        entitlement.shareQuantity(),
                        "entitlement:" + entitlement.id()
                ))
                .toList();
        requireChunkCount(
                "share entitlement processing-ledger insert",
                entitlements.size(),
                processingLedger.completeAccounts(actionId, operation, today, completions, now)
        );
        return entitlements.size();
    }

    private int applyDueStockSplits(LocalDate today, List<RuntimeException> failures) {
        List<StockSplitActionRow> rows = corporateActionReader.findDueStockSplits(
                today,
                STOCK_SPLIT,
                ANNOUNCED,
                normalizedActionBatchLimit()
        );
        Set<String> symbolsWithOpenOrders = orderBookOrderGuard.findSymbolsWithOpenOrders(rows, StockSplitActionRow::symbol);

        int processed = 0;
        for (StockSplitActionRow row : rows) {
            if (row.splitTo() % row.splitFrom() != 0 || symbolsWithOpenOrders.contains(row.symbol())) {
                continue;
            }
            processed += executeChunkedAction(
                    "stock-split",
                    row.id(),
                    failures,
                    () -> applyDueStockSplitInChunks(row, today, failures)
            );
        }
        return processed;
    }

    private int applyDueStockSplitInChunks(
            StockSplitActionRow row,
            LocalDate today,
            List<RuntimeException> failures
    ) {
        String operation = "stock-split";
        if (isOperationCompleted(row.id(), operation, today)) {
            return 0;
        }
        long afterAccountId = 0L;
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            long cursor = afterAccountId;
            AccountIdChunkResult chunk = transactionExecutor.executeValue(
                    () -> applyStockSplitHoldingChunk(row, today, operation, cursor, chunkSize)
            );
            if (chunk == null || chunk.selectedCount() == 0) {
                break;
            }
            afterAccountId = chunk.lastAccountId();
            if (chunk.selectedCount() < chunkSize) {
                break;
            }
        }
        return executeActionInTransaction(
                operation,
                row.id(),
                today,
                failures,
                () -> finalizeStockSplit(row, today)
        );
    }

    private AccountIdChunkResult applyStockSplitHoldingChunk(
            StockSplitActionRow row,
            LocalDate today,
            String operation,
            long afterAccountId,
            int chunkSize
    ) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, STOCK_SPLIT, ANNOUNCED, "listing_date")) {
            return AccountIdChunkResult.empty(afterAccountId);
        }
        if (!orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(row), StockSplitActionRow::symbol).isEmpty()) {
            return AccountIdChunkResult.empty(afterAccountId);
        }
        List<Long> accountIds = corporateActionReader.findHoldingAccountChunkForSplit(
                row.id(),
                row.symbol(),
                operation,
                today,
                afterAccountId,
                chunkSize
        );
        int multiplier = row.splitTo() / row.splitFrom();
        BigDecimal priceDivisor = BigDecimal.valueOf(multiplier);
        LocalDateTime now = currentDateTime();
        if (accountIds.isEmpty()) {
            return AccountIdChunkResult.empty(afterAccountId);
        }
        requireChunkCount(
                "stock split holding lock",
                accountIds.size(),
                corporateActionWriter.lockHoldingChunkForSplit(row.symbol(), accountIds)
        );
        requireChunkCount(
                "stock split holding update",
                accountIds.size(),
                corporateActionWriter.multiplyHoldingChunkForSplit(
                        row.symbol(),
                        accountIds,
                        multiplier,
                        priceDivisor,
                        now
                )
        );
        List<CorporateActionProcessingLedger.AccountCompletion> completions = accountIds.stream()
                .map(accountId -> new CorporateActionProcessingLedger.AccountCompletion(
                        accountId,
                        1,
                        BigDecimal.ZERO,
                        0L,
                        "holding:" + accountId + ":" + row.symbol()
                ))
                .toList();
        requireChunkCount(
                "stock split processing-ledger insert",
                accountIds.size(),
                processingLedger.completeAccounts(row.id(), operation, today, completions, now)
        );
        return AccountIdChunkResult.of(accountIds, afterAccountId);
    }

    private int finalizeStockSplit(StockSplitActionRow row, LocalDate today) {
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
        corporateActionWriter.adjustPriceForSplit(row.symbol(), priceDivisor, now);
        corporateActionPriceWriter.insertCurrentPriceTick(row.symbol(), "corporate-action-split", now);
        return updatedAction;
    }

    private int applyDueDelistings(LocalDate today, List<RuntimeException> failures) {
        List<DelistingActionRow> rows = corporateActionReader.findDueDelistings(
                today,
                DELISTING,
                ANNOUNCED,
                normalizedActionBatchLimit()
        );

        int processed = 0;
        for (DelistingActionRow row : rows) {
            processed += executeChunkedAction(
                    "delisting",
                    row.id(),
                    failures,
                    () -> applyDueDelistingInChunks(row, today, failures)
            );
        }
        return processed;
    }

    private int applyDueDelistingInChunks(
            DelistingActionRow row,
            LocalDate today,
            List<RuntimeException> failures
    ) {
        String operation = "delisting";
        if (isOperationCompleted(row.id(), operation, today)) {
            return 0;
        }
        int chunkSize = normalizedAccountChunkSize();
        while (true) {
            Integer selectedCount = transactionExecutor.executeValue(
                    () -> cancelDelistingOrderChunk(row, today, chunkSize)
            );
            if (selectedCount == null || selectedCount == 0) {
                break;
            }
        }
        return executeActionInTransaction(
                operation,
                row.id(),
                today,
                failures,
                () -> finalizeDelisting(row, today)
        );
    }

    private int cancelDelistingOrderChunk(DelistingActionRow row, LocalDate today, int chunkSize) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, DELISTING, ANNOUNCED, "delisting_date")) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        return orderBookOrderGuard.cancelOpenOrderBookOrderChunk(row.symbol(), now, chunkSize);
    }

    private int finalizeDelisting(DelistingActionRow row, LocalDate today) {
        if (!corporateActionReader.lockDueActionForUpdate(
                row.id(), today, DELISTING, ANNOUNCED, "delisting_date")) {
            return 0;
        }
        if (!orderBookOrderGuard.findSymbolsWithOpenOrders(List.of(row), DelistingActionRow::symbol).isEmpty()) {
            return 0;
        }
        LocalDateTime now = currentDateTime();
        int updatedAction = corporateActionWriter.markActionDelisted(row.id(), DELISTED, ANNOUNCED, now);
        if (updatedAction == 0) {
            return 0;
        }
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

    private int executeActionInTransaction(
            String operation,
            long actionId,
            LocalDate effectiveBusinessDate,
            List<RuntimeException> failures,
            Supplier<Integer> action
    ) {
        try {
            return transactionExecutor.execute(() -> {
                if (processingLedger.isCompleted(actionId, operation, effectiveBusinessDate)) {
                    return 0;
                }
                int processedCount = action.get();
                if (processedCount > 0) {
                    processingLedger.complete(
                            actionId,
                            operation,
                            effectiveBusinessDate,
                            processedCount,
                            currentDateTime()
                    );
                }
                return processedCount;
            });
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

    private int executeChunkedAction(
            String operation,
            long actionId,
            List<RuntimeException> failures,
            Supplier<Integer> action
    ) {
        try {
            return action.get();
        } catch (RuntimeException ex) {
            log.error(
                    "Corporate action chunks failed after prior chunks may have committed: operation={}, actionId={}, reason={}",
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

    private boolean isOperationCompleted(long actionId, String operation, LocalDate effectiveBusinessDate) {
        Boolean completed = transactionExecutor.executeValue(
                () -> processingLedger.isCompleted(actionId, operation, effectiveBusinessDate)
        );
        return Boolean.TRUE.equals(completed);
    }

    private void completeOperation(
            long actionId,
            String operation,
            LocalDate effectiveBusinessDate,
            int currentAttemptProcessedCount
    ) {
        transactionExecutor.execute(() -> {
            if (!processingLedger.isCompleted(actionId, operation, effectiveBusinessDate)) {
                int cumulativeProcessedCount = processingLedger.sumCompletedAccountProcessedCount(
                        actionId,
                        operation,
                        effectiveBusinessDate
                );
                processingLedger.complete(
                        actionId,
                        operation,
                        effectiveBusinessDate,
                        Math.max(cumulativeProcessedCount, currentAttemptProcessedCount),
                        currentDateTime()
                );
            }
            return 0;
        });
    }

    private CorporateActionProcessingLedger.AccountCompletion accountDecision(
            long accountId,
            int processedCount,
            BigDecimal amount,
            long quantity,
            String ledgerReferenceId
    ) {
        return new CorporateActionProcessingLedger.AccountCompletion(
                accountId,
                processedCount,
                amount,
                quantity,
                ledgerReferenceId
        );
    }

    private void requireChunkCount(String operation, int expectedCount, int actualCount) {
        if (actualCount != expectedCount) {
            throw new IllegalStateException(
                    "%s count mismatch: expected=%d, actual=%d"
                            .formatted(operation, expectedCount, actualCount)
            );
        }
    }

    private int normalizedAccountChunkSize() {
        return validateAccountChunkSize(accountChunkSize);
    }

    private int normalizedActionBatchLimit() {
        return validateActionBatchLimit(actionBatchLimit);
    }

    static int validateAccountChunkSize(int chunkSize) {
        if (chunkSize < 1 || chunkSize > MAX_ACCOUNT_CHUNK_SIZE) {
            throw new IllegalStateException(
                    "stock.batch.corporate-action.account-chunk-size must be between 1 and %d: %d"
                            .formatted(MAX_ACCOUNT_CHUNK_SIZE, chunkSize)
            );
        }
        return chunkSize;
    }

    static int validateActionBatchLimit(int limit) {
        if (limit < 1 || limit > MAX_ACTION_BATCH_LIMIT) {
            throw new IllegalStateException(
                    "stock.batch.corporate-action.action-batch-limit must be between 1 and %d: %d"
                            .formatted(MAX_ACTION_BATCH_LIMIT, limit)
            );
        }
        return limit;
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

    private record AccountChunkResult(
            int selectedCount,
            int processedCount,
            long lastAccountId,
            boolean exhausted
    ) {

        private static AccountChunkResult empty(long lastAccountId) {
            return new AccountChunkResult(0, 0, lastAccountId, false);
        }

        private static AccountChunkResult exhausted(long lastAccountId) {
            return new AccountChunkResult(0, 0, lastAccountId, true);
        }

        private static AccountChunkResult of(
                List<AutoParticipantCapitalIncreaseCandidate> candidates,
                int processedCount,
                long fallbackAccountId,
                boolean exhausted
        ) {
            long lastAccountId = candidates.isEmpty()
                    ? fallbackAccountId
                    : candidates.get(candidates.size() - 1).accountId();
            return new AccountChunkResult(candidates.size(), processedCount, lastAccountId, exhausted);
        }
    }

    private record AccountIdChunkResult(int selectedCount, long lastAccountId) {

        private static AccountIdChunkResult empty(long lastAccountId) {
            return new AccountIdChunkResult(0, lastAccountId);
        }

        private static AccountIdChunkResult of(List<Long> accountIds, long fallbackAccountId) {
            long lastAccountId = accountIds.isEmpty()
                    ? fallbackAccountId
                    : accountIds.get(accountIds.size() - 1);
            return new AccountIdChunkResult(accountIds.size(), lastAccountId);
        }
    }

}
