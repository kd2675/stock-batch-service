package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.StockOrderOriginType;

@Component
class IssueUnderwriterSupplyPlanner {

    private static final BigDecimal MIN_RATE = new BigDecimal("0.000001");
    private static final BigDecimal MAX_RATE = BigDecimal.ONE;

    @Value("${stock.batch.issue-underwriter-market.daily-submission-rate:0.100000}")
    private BigDecimal dailySubmissionRate = new BigDecimal("0.100000");

    @Value("${stock.batch.issue-underwriter-market.single-order-rate:0.020000}")
    private BigDecimal singleOrderRate = new BigDecimal("0.020000");

    @Value("${stock.batch.issue-underwriter-market.external-bid-depth-rate:0.100000}")
    private BigDecimal externalBidDepthRate = new BigDecimal("0.100000");

    @Value("${stock.batch.issue-underwriter-market.order-ttl-seconds:600}")
    private int orderTtlSeconds = 600;

    @Value("${stock.batch.issue-underwriter-market.daily-order-limit:20}")
    private int dailyOrderLimit = 20;

    @PostConstruct
    void validateConfiguration() {
        requireRate("daily-submission-rate", dailySubmissionRate);
        requireRate("single-order-rate", singleOrderRate);
        requireRate("external-bid-depth-rate", externalBidDepthRate);
        if (singleOrderRate.compareTo(dailySubmissionRate) > 0) {
            throw new IllegalStateException(
                    "Issue-underwriter single-order rate cannot exceed the daily submission rate"
            );
        }
        if (orderTtlSeconds < 60 || orderTtlSeconds > 3_600) {
            throw new IllegalStateException(
                    "stock.batch.issue-underwriter-market.order-ttl-seconds "
                            + "must be between 60 and 3600"
            );
        }
        if (dailyOrderLimit < 1 || dailyOrderLimit > 100) {
            throw new IllegalStateException(
                    "stock.batch.issue-underwriter-market.daily-order-limit "
                            + "must be between 1 and 100"
            );
        }
    }

    SupplyPlan plan(SupplyInput input) {
        IssueUnderwriterSupplyRepository.ContractSnapshot contract = input.contract();
        List<AutoOrder> allOpenOrders = input.openOrders().allRows();
        if (!contract.active()) {
            return gated(input, allOpenOrders, "SUSPENDED", "CONTRACT_NOT_ACTIVE", false);
        }
        if (!input.marketTradingEnabled()) {
            return gated(input, allOpenOrders, "GATED", "MARKET_NOT_ENABLED", false);
        }
        if (!input.account().supplyReconciled(contract)) {
            return gated(
                    input,
                    allOpenOrders,
                    "GATED",
                    "SUPPLY_RECONCILIATION_FAILED",
                    false
            );
        }
        if (!input.account().roleEligible(contract)) {
            return gated(input, allOpenOrders, "GATED", "ROLE_RECONCILIATION_FAILED", false);
        }
        if (contract.stabilizationStartDate() == null
                || contract.stabilizationEndDate() == null
                || contract.stabilizationQuantityLimit() <= 0L
                || contract.stabilizationAmountLimit().signum() <= 0) {
            return gated(input, allOpenOrders, "GATED", "SUPPLY_POLICY_INCOMPLETE", false);
        }
        if (input.simulationTradeDate().isBefore(contract.stabilizationStartDate())) {
            return gated(input, allOpenOrders, "GATED", "WINDOW_NOT_STARTED", false);
        }
        if (input.simulationTradeDate().isAfter(contract.stabilizationEndDate())) {
            return gated(input, allOpenOrders, "COMPLETED", "WINDOW_ENDED", true);
        }
        if (input.openOrders().reconciliationMismatchCount() > 0) {
            return gated(
                    input,
                    List.of(),
                    "GATED",
                    "OPEN_ORDER_RECONCILIATION_FAILED",
                    false
            );
        }
        if (input.openOrders().overflow()) {
            return gated(input, allOpenOrders, "GATED", "OPEN_ORDER_OVERFLOW", false);
        }
        if (!allOpenOrders.isEmpty()) {
            AutoOrder existing = allOpenOrders.getFirst();
            if (!"SELL".equals(existing.side())) {
                return gated(input, allOpenOrders, "GATED", "INVALID_OPEN_ORDER_SIDE", false);
            }
            if (existing.expiresAt() != null
                    && !existing.expiresAt().isAfter(input.now())) {
                return gated(input, allOpenOrders, "GATED", "EXPIRED_ORDER_CANCELLED", false);
            }
            return gated(
                    input,
                    List.of(),
                    "ACTIVE",
                    lifetimeLimitReached(input)
                            ? "LIFETIME_LIMIT_PENDING_OPEN_ORDER"
                            : "OPEN_ORDER_RETAINED",
                    false
            );
        }
        if (lifetimeLimitReached(input)) {
            return gated(input, List.of(), "COMPLETED", "LIFETIME_LIMIT_REACHED", true);
        }
        if (input.referenceDailyVolume() <= 0L) {
            return gated(input, List.of(), "GATED", "REFERENCE_VOLUME_MISSING", false);
        }

        long dailyQuantityLimit = input.dailyState().persisted()
                ? input.dailyState().submissionQuantityLimit()
                : scaledQuantity(input.referenceDailyVolume(), dailySubmissionRate);
        BigDecimal dailyAmountLimit = input.dailyState().persisted()
                ? input.dailyState().submissionAmountLimit()
                : input.marketConfig().currentPrice()
                .multiply(BigDecimal.valueOf(dailyQuantityLimit))
                .setScale(2, RoundingMode.HALF_UP);
        long remainingDailyQuantity = Math.max(
                0L,
                dailyQuantityLimit - input.dailyState().submittedQuantity()
        );
        BigDecimal remainingDailyAmount = nonNegative(
                dailyAmountLimit.subtract(input.dailyState().submittedAmount())
        );
        if (remainingDailyQuantity <= 0L || remainingDailyAmount.signum() <= 0) {
            return gated(
                    input,
                    List.of(),
                    "GATED",
                    "DAILY_SUBMISSION_LIMIT_REACHED",
                    false,
                    dailyQuantityLimit,
                    dailyAmountLimit
            );
        }
        if (input.dailyState().generatedOrderCount() >= dailyOrderLimit) {
            return gated(
                    input,
                    List.of(),
                    "GATED",
                    "DAILY_ORDER_LIMIT_REACHED",
                    false,
                    dailyQuantityLimit,
                    dailyAmountLimit
            );
        }

        BigDecimal orderPrice = passiveSellPrice(input);
        if (orderPrice == null) {
            return gated(
                    input,
                    List.of(),
                    "GATED",
                    "PASSIVE_PRICE_UNAVAILABLE",
                    false,
                    dailyQuantityLimit,
                    dailyAmountLimit
            );
        }
        long quantity = scaledQuantity(input.referenceDailyVolume(), singleOrderRate);
        if (input.externalBook().topFiveBidDepth() > 0L) {
            quantity = Math.min(
                    quantity,
                    scaledQuantity(
                            input.externalBook().topFiveBidDepth(),
                            externalBidDepthRate
                    )
            );
        }
        quantity = Math.min(quantity, remainingDailyQuantity);
        quantity = Math.min(quantity, input.account().availableQuantity());
        quantity = Math.min(
                quantity,
                Math.max(
                        0L,
                        contract.stabilizationQuantityLimit()
                                - input.supplyUsage().submittedQuantity()
                )
        );
        quantity = Math.min(
                quantity,
                affordableQuantity(remainingDailyAmount, orderPrice)
        );
        quantity = Math.min(
                quantity,
                affordableQuantity(
                        nonNegative(
                                contract.stabilizationAmountLimit()
                                        .subtract(input.supplyUsage().submittedAmount())
                        ),
                        orderPrice
                )
        );
        if (quantity <= 0L) {
            return gated(
                    input,
                    List.of(),
                    "GATED",
                    "NO_SUPPLY_CAPACITY",
                    false,
                    dailyQuantityLimit,
                    dailyAmountLimit
            );
        }

        BigDecimal submittedAmount = orderPrice
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
        AutoMarketPlannedOrder plannedOrder = new AutoMarketPlannedOrder(
                contract.accountId(),
                contract.symbol(),
                "SELL",
                orderPrice,
                quantity,
                null,
                null,
                input.now().plusSeconds(orderTtlSeconds),
                null,
                null,
                StockOrderOriginType.ISSUE_UNDERWRITER,
                AutoMarketOrderStrategyOrigin.issueUnderwriter(
                        contract.participantId(),
                        contract.id(),
                        contract.policyVersion()
                )
        );
        return new SupplyPlan(
                input.referenceDailyVolume(),
                dailyQuantityLimit,
                dailyAmountLimit,
                List.of(),
                List.of(plannedOrder),
                quantity,
                submittedAmount,
                orderPrice,
                "ACTIVE",
                "WITHIN_LIMITS",
                false
        );
    }

    private SupplyPlan gated(
            SupplyInput input,
            List<AutoOrder> cancellationOrders,
            String stateStatus,
            String gateReason,
            boolean completeContract
    ) {
        long dailyQuantityLimit = input.dailyState().persisted()
                ? input.dailyState().submissionQuantityLimit()
                : input.referenceDailyVolume() <= 0L
                ? 0L
                : scaledQuantity(input.referenceDailyVolume(), dailySubmissionRate);
        BigDecimal dailyAmountLimit = input.dailyState().persisted()
                ? input.dailyState().submissionAmountLimit()
                : input.marketConfig().currentPrice()
                .multiply(BigDecimal.valueOf(dailyQuantityLimit))
                .setScale(2, RoundingMode.HALF_UP);
        return gated(
                input,
                cancellationOrders,
                stateStatus,
                gateReason,
                completeContract,
                dailyQuantityLimit,
                dailyAmountLimit
        );
    }

    private SupplyPlan gated(
            SupplyInput input,
            List<AutoOrder> cancellationOrders,
            String stateStatus,
            String gateReason,
            boolean completeContract,
            long dailyQuantityLimit,
            BigDecimal dailyAmountLimit
    ) {
        return new SupplyPlan(
                Math.max(0L, input.referenceDailyVolume()),
                Math.max(0L, dailyQuantityLimit),
                nonNegative(dailyAmountLimit),
                List.copyOf(cancellationOrders),
                List.of(),
                0L,
                BigDecimal.ZERO.setScale(2),
                null,
                stateStatus,
                gateReason,
                completeContract
        );
    }

    private boolean lifetimeLimitReached(SupplyInput input) {
        return input.supplyUsage().submittedQuantity()
                >= input.contract().stabilizationQuantityLimit()
                || input.supplyUsage().submittedAmount()
                .compareTo(input.contract().stabilizationAmountLimit()) >= 0;
    }

    private BigDecimal passiveSellPrice(SupplyInput input) {
        AutoMarketConfig config = input.marketConfig();
        BigDecimal price = AutoMarketPricePolicy.moveByTicks(
                config.market(),
                config.currentPrice(),
                1
        ).max(input.contract().issuePrice());
        if (input.externalBook().bestAsk() != null) {
            price = price.max(input.externalBook().bestAsk());
        }
        if (input.externalBook().bestBid() != null
                && price.compareTo(input.externalBook().bestBid()) <= 0) {
            price = AutoMarketPricePolicy.moveByTicks(
                    config.market(),
                    input.externalBook().bestBid(),
                    1
            );
        }
        BigDecimal normalized = AutoMarketPricePolicy.normalizePriceWithinDailyLimit(
                price,
                config,
                config.tickSize()
        );
        if (normalized.compareTo(input.contract().issuePrice()) < 0) {
            return null;
        }
        if (input.externalBook().bestBid() != null
                && normalized.compareTo(input.externalBook().bestBid()) <= 0) {
            return null;
        }
        return normalized;
    }

    private long scaledQuantity(long base, BigDecimal rate) {
        if (base <= 0L) {
            return 0L;
        }
        return Math.max(
                1L,
                BigDecimal.valueOf(base)
                        .multiply(rate)
                        .setScale(0, RoundingMode.DOWN)
                        .longValueExact()
        );
    }

    private long affordableQuantity(BigDecimal amount, BigDecimal price) {
        if (amount == null || price == null
                || amount.signum() <= 0 || price.signum() <= 0) {
            return 0L;
        }
        return amount.divide(price, 0, RoundingMode.DOWN).longValueExact();
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void requireRate(String property, BigDecimal value) {
        if (value == null
                || value.compareTo(MIN_RATE) < 0
                || value.compareTo(MAX_RATE) > 0) {
            throw new IllegalStateException(
                    "stock.batch.issue-underwriter-market." + property
                            + " must be between 0.000001 and 1"
            );
        }
    }

    record SupplyInput(
            IssueUnderwriterSupplyRepository.ContractSnapshot contract,
            AutoMarketConfig marketConfig,
            boolean marketTradingEnabled,
            LocalDateTime now,
            java.time.LocalDate simulationTradeDate,
            IssueUnderwriterSupplyRepository.AccountSnapshot account,
            IssueUnderwriterSupplyRepository.OpenOrderLoad openOrders,
            IssueUnderwriterSupplyRepository.ExternalBook externalBook,
            long referenceDailyVolume,
            IssueUnderwriterSupplyRepository.DailyState dailyState,
            IssueUnderwriterSupplyRepository.SupplyUsage supplyUsage
    ) {
    }

    record SupplyPlan(
            long referenceDailyVolume,
            long dailyQuantityLimit,
            BigDecimal dailyAmountLimit,
            List<AutoOrder> cancellationOrders,
            List<AutoMarketPlannedOrder> executableOrders,
            long submittedQuantity,
            BigDecimal submittedAmount,
            BigDecimal orderPrice,
            String stateStatus,
            String gateReason,
            boolean completeContract
    ) {
        SupplyPlan {
            cancellationOrders = List.copyOf(cancellationOrders);
            executableOrders = List.copyOf(executableOrders);
        }
    }
}
