package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketPressure;

@Component
class InstitutionPortfolioPlanner {

    private static final int RATE_SCALE = 8;
    private static final int PRESSURE_SCALE = 6;
    private static final int MONEY_SCALE = 2;
    private static final double TARGET_STYLE_RESPONSE = 0.20;
    private static final double MOMENTUM_NORMALIZATION = 0.10;
    private static final double VALUE_NORMALIZATION = 0.20;
    private static final double EPSILON = 1.0e-10;
    private static final BigDecimal LIVE_SINGLE_DECISION_REFERENCE_RATE =
            new BigDecimal("0.005000");

    InstitutionDecisionPlan plan(
            InstitutionPortfolioPolicy policy,
            List<InstitutionSymbolMandate> mandates,
            Map<String, InstitutionMarketInput> marketInputs,
            Map<String, InstitutionPositionSnapshot> positions,
            Map<String, InstitutionDailyBudgetSnapshot> dailyBudgets,
            Map<String, InstitutionDecisionAction> previousActions,
            BigDecimal availableCash,
            BigDecimal openBuyReservedCash,
            BigDecimal totalHoldingValue
    ) {
        Objects.requireNonNull(policy, "Institution portfolio policy is required");
        List<InstitutionSymbolMandate> orderedMandates = normalizedMandates(mandates);
        validateInputs(policy, orderedMandates, marketInputs, dailyBudgets);

        BigDecimal cash = nonNegative(availableCash);
        BigDecimal reservedCash = nonNegative(openBuyReservedCash);
        BigDecimal holdingValue = nonNegative(totalHoldingValue);
        BigDecimal liquidAssetAmount = money(cash.add(reservedCash).add(holdingValue));
        Map<String, Double> normalizedBaseWeights = normalizedBaseWeights(orderedMandates);
        Map<String, BlendedPressure> blendedPressures = blendedPressures(
                policy,
                orderedMandates,
                marketInputs
        );
        double unconstrainedTargetStockAllocation = targetStockAllocation(
                policy,
                orderedMandates,
                normalizedBaseWeights,
                blendedPressures
        );
        double targetStockAllocation = clampToMandateCapacity(
                unconstrainedTargetStockAllocation,
                orderedMandates
        );

        Map<String, Double> targetAllocations = targetAllocations(
                targetStockAllocation,
                orderedMandates,
                normalizedBaseWeights,
                blendedPressures,
                marketInputs
        );
        BigDecimal defaultGrossNotionalLimit = moneyDown(
                liquidAssetAmount.multiply(policy.dailyTurnoverLimitRate())
        );
        Map<String, InstitutionDailyBudgetSnapshot> resolvedBudgets = resolveBudgets(
                policy,
                orderedMandates,
                dailyBudgets,
                defaultGrossNotionalLimit
        );
        BigDecimal grossNotionalLimit = resolvedBudgets.values().stream()
                .map(InstitutionDailyBudgetSnapshot::grossNotionalLimit)
                .min(BigDecimal::compareTo)
                .orElse(defaultGrossNotionalLimit);
        BigDecimal previouslyPlannedAmount = dailyBudgets.values().stream()
                .map(InstitutionDailyBudgetSnapshot::plannedGrossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingDailyNotionalBefore = nonNegative(
                grossNotionalLimit.subtract(previouslyPlannedAmount)
        );
        BigDecimal decisionNotionalLimit = moneyDown(
                liquidAssetAmount.multiply(policy.maxDecisionTurnoverRate())
        );
        BigDecimal sharedNotionalLimit = remainingDailyNotionalBefore.min(decisionNotionalLimit);

        List<Draft> drafts = createDrafts(
                policy,
                orderedMandates,
                normalizedBaseWeights,
                targetStockAllocation,
                targetAllocations,
                blendedPressures,
                marketInputs,
                positions,
                resolvedBudgets,
                previousActions,
                liquidAssetAmount
        );
        applySharedNotionalLimit(
                drafts,
                sharedNotionalLimit,
                remainingDailyNotionalBefore,
                decisionNotionalLimit
        );
        applyCashLimit(drafts, cash);
        finalizeQuantities(drafts);

        BigDecimal plannedThisDecision = drafts.stream()
                .map(Draft::gatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingDailyNotionalAfter = moneyDown(
                nonNegative(remainingDailyNotionalBefore.subtract(plannedThisDecision))
        );
        List<InstitutionDecisionItem> items = drafts.stream()
                .map(draft -> draft.toDecisionItem(
                        liquidAssetAmount,
                        targetStockAllocation,
                        remainingDailyNotionalAfter
                ))
                .toList();
        return new InstitutionDecisionPlan(
                liquidAssetAmount,
                rate(targetStockAllocation),
                items
        );
    }

    private List<InstitutionSymbolMandate> normalizedMandates(List<InstitutionSymbolMandate> mandates) {
        if (mandates == null || mandates.isEmpty()) {
            throw new IllegalStateException("Active institution portfolio requires at least one enabled mandate");
        }
        List<InstitutionSymbolMandate> ordered = mandates.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(InstitutionSymbolMandate::symbol))
                .toList();
        Set<String> symbols = ordered.stream()
                .map(InstitutionSymbolMandate::symbol)
                .collect(Collectors.toSet());
        if (symbols.size() != ordered.size()) {
            throw new IllegalStateException("Institution portfolio contains duplicate symbol mandates");
        }
        return ordered;
    }

    private void validateInputs(
            InstitutionPortfolioPolicy policy,
            List<InstitutionSymbolMandate> mandates,
            Map<String, InstitutionMarketInput> marketInputs,
            Map<String, InstitutionDailyBudgetSnapshot> dailyBudgets
    ) {
        if (!"LIVE".equals(policy.executionMode())) {
            throw new IllegalStateException(
                    "Institution planner cannot process execution mode " + policy.executionMode()
            );
        }
        for (InstitutionSymbolMandate mandate : mandates) {
            InstitutionMarketInput input = marketInputs.get(mandate.symbol());
            if (input == null) {
                throw new IllegalStateException(
                        "Enabled institution mandate has no enabled market configuration: " + mandate.symbol()
                );
            }
        }
        for (InstitutionDailyBudgetSnapshot budget : dailyBudgets.values()) {
            if (budget.policyVersion() != policy.policyVersion()) {
                throw new IllegalStateException(
                        "Institution policy cannot change after the daily budget is established"
                );
            }
        }
    }

    private Map<String, Double> normalizedBaseWeights(List<InstitutionSymbolMandate> mandates) {
        double sum = mandates.stream()
                .map(InstitutionSymbolMandate::baseSymbolWeight)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        if (!Double.isFinite(sum) || sum <= 0.0) {
            throw new IllegalStateException("Institution mandate base weights must have a positive sum");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (InstitutionSymbolMandate mandate : mandates) {
            result.put(mandate.symbol(), mandate.baseSymbolWeight().doubleValue() / sum);
        }
        return Map.copyOf(result);
    }

    private Map<String, BlendedPressure> blendedPressures(
            InstitutionPortfolioPolicy policy,
            List<InstitutionSymbolMandate> mandates,
            Map<String, InstitutionMarketInput> marketInputs
    ) {
        double primaryWeight = Math.clamp(policy.primaryRegimeWeight().doubleValue(), 0.0, 1.0);
        Map<String, BlendedPressure> result = new LinkedHashMap<>();
        for (InstitutionSymbolMandate mandate : mandates) {
            InstitutionMarketInput input = marketInputs.get(mandate.symbol());
            result.put(
                    mandate.symbol(),
                    new BlendedPressure(
                            blend(input.primaryPressure().price(), input.secondaryPressure().price(), primaryWeight),
                            blend(
                                    input.primaryPressure().assetPreference(),
                                    input.secondaryPressure().assetPreference(),
                                    primaryWeight
                            ),
                            blend(
                                    input.primaryPressure().volatility(),
                                    input.secondaryPressure().volatility(),
                                    primaryWeight
                            ),
                            blend(
                                    input.primaryPressure().liquidity(),
                                    input.secondaryPressure().liquidity(),
                                    primaryWeight
                            ),
                            blend(
                                    input.primaryPressure().executionAggression(),
                                    input.secondaryPressure().executionAggression(),
                                    primaryWeight
                            )
                    )
            );
        }
        return Map.copyOf(result);
    }

    private double targetStockAllocation(
            InstitutionPortfolioPolicy policy,
            List<InstitutionSymbolMandate> mandates,
            Map<String, Double> normalizedBaseWeights,
            Map<String, BlendedPressure> pressures
    ) {
        double weightedAssetPreference = 0.0;
        double weightedVolatility = 0.0;
        for (InstitutionSymbolMandate mandate : mandates) {
            double weight = normalizedBaseWeights.get(mandate.symbol());
            BlendedPressure pressure = pressures.get(mandate.symbol());
            weightedAssetPreference += weight * pressure.assetPreference();
            weightedVolatility += weight * pressure.volatility();
        }
        double target = policy.baseStockAllocationRate().doubleValue()
                + policy.assetPreferenceSensitivity().doubleValue() * weightedAssetPreference
                - policy.volatilitySensitivity().doubleValue() * Math.abs(weightedVolatility);
        return Math.clamp(
                target,
                policy.minStockAllocationRate().doubleValue(),
                policy.maxStockAllocationRate().doubleValue()
        );
    }

    private double clampToMandateCapacity(
            double targetStockAllocation,
            List<InstitutionSymbolMandate> mandates
    ) {
        double minimum = mandates.stream()
                .map(InstitutionSymbolMandate::minPortfolioAllocationRate)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        double maximum = mandates.stream()
                .map(InstitutionSymbolMandate::maxPortfolioAllocationRate)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        if (minimum > maximum + EPSILON || maximum <= 0.0) {
            throw new IllegalStateException("Institution mandate allocation bounds are infeasible");
        }
        return Math.clamp(targetStockAllocation, minimum, maximum);
    }

    private Map<String, Double> targetAllocations(
            double targetStockAllocation,
            List<InstitutionSymbolMandate> mandates,
            Map<String, Double> normalizedBaseWeights,
            Map<String, BlendedPressure> pressures,
            Map<String, InstitutionMarketInput> marketInputs
    ) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (InstitutionSymbolMandate mandate : mandates) {
            InstitutionMarketInput input = marketInputs.get(mandate.symbol());
            BlendedPressure pressure = pressures.get(mandate.symbol());
            double momentum = Math.clamp(input.return5Day() / MOMENTUM_NORMALIZATION, -1.0, 1.0);
            double value = Math.clamp(-input.return20Day() / VALUE_NORMALIZATION, -1.0, 1.0);
            double styleSignal = mandate.pricePressureSensitivity().doubleValue() * pressure.price()
                    + mandate.momentumSensitivity().doubleValue() * momentum
                    + mandate.valueSensitivity().doubleValue() * value
                    + mandate.reportSensitivity().doubleValue() * input.reportPressure();
            double multiplier = Math.clamp(1.0 + TARGET_STYLE_RESPONSE * styleSignal, 0.50, 1.50);
            scores.put(mandate.symbol(), Math.max(EPSILON, normalizedBaseWeights.get(mandate.symbol()) * multiplier));
        }
        return boundedNormalize(targetStockAllocation, mandates, scores);
    }

    private Map<String, Double> boundedNormalize(
            double target,
            List<InstitutionSymbolMandate> mandates,
            Map<String, Double> scores
    ) {
        double scoreSum = scores.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> allocations = new LinkedHashMap<>();
        for (InstitutionSymbolMandate mandate : mandates) {
            double raw = target * scores.get(mandate.symbol()) / scoreSum;
            allocations.put(
                    mandate.symbol(),
                    Math.clamp(
                            raw,
                            mandate.minPortfolioAllocationRate().doubleValue(),
                            mandate.maxPortfolioAllocationRate().doubleValue()
                    )
            );
        }
        for (int iteration = 0; iteration < 32; iteration++) {
            double current = allocations.values().stream().mapToDouble(Double::doubleValue).sum();
            double residual = target - current;
            if (Math.abs(residual) <= EPSILON) {
                break;
            }
            boolean adding = residual > 0.0;
            List<InstitutionSymbolMandate> candidates = mandates.stream()
                    .filter(mandate -> adding
                            ? allocations.get(mandate.symbol())
                                    < mandate.maxPortfolioAllocationRate().doubleValue() - EPSILON
                            : allocations.get(mandate.symbol())
                                    > mandate.minPortfolioAllocationRate().doubleValue() + EPSILON)
                    .toList();
            if (candidates.isEmpty()) {
                break;
            }
            double capacitySum = candidates.stream()
                    .mapToDouble(mandate -> adding
                            ? mandate.maxPortfolioAllocationRate().doubleValue()
                                    - allocations.get(mandate.symbol())
                            : allocations.get(mandate.symbol())
                                    - mandate.minPortfolioAllocationRate().doubleValue())
                    .sum();
            if (capacitySum <= EPSILON) {
                break;
            }
            for (InstitutionSymbolMandate mandate : candidates) {
                String symbol = mandate.symbol();
                double capacity = adding
                        ? mandate.maxPortfolioAllocationRate().doubleValue() - allocations.get(symbol)
                        : allocations.get(symbol) - mandate.minPortfolioAllocationRate().doubleValue();
                double adjustment = Math.min(Math.abs(residual) * capacity / capacitySum, capacity);
                allocations.put(symbol, allocations.get(symbol) + (adding ? adjustment : -adjustment));
            }
        }
        double unresolved = target - allocations.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(unresolved) > 1.0e-7) {
            throw new IllegalStateException("Institution target allocation cannot satisfy mandate bounds");
        }
        return Map.copyOf(allocations);
    }

    private Map<String, InstitutionDailyBudgetSnapshot> resolveBudgets(
            InstitutionPortfolioPolicy policy,
            List<InstitutionSymbolMandate> mandates,
            Map<String, InstitutionDailyBudgetSnapshot> existing,
            BigDecimal defaultGrossNotionalLimit
    ) {
        Map<String, InstitutionDailyBudgetSnapshot> resolved = new LinkedHashMap<>();
        for (InstitutionSymbolMandate mandate : mandates) {
            long quantityLimit = Math.max(
                    1L,
                    floorToLong(
                            BigDecimal.valueOf(mandate.referenceDailyVolume())
                                    .multiply(mandate.dailyParticipationRate())
                    )
            );
            InstitutionDailyBudgetSnapshot budget = existing.get(mandate.symbol());
            if (budget == null) {
                budget = new InstitutionDailyBudgetSnapshot(
                        mandate.referenceDailyVolume(),
                        quantityLimit,
                        defaultGrossNotionalLimit,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        policy.policyVersion(),
                        0L
                );
            } else {
                if (budget.referenceDailyVolume() != mandate.referenceDailyVolume()
                        || budget.grossQuantityLimit() != quantityLimit) {
                    throw new IllegalStateException(
                            "Institution mandate cannot change after the daily budget is established: "
                                    + mandate.symbol()
                    );
                }
            }
            resolved.put(mandate.symbol(), budget);
        }
        return Map.copyOf(resolved);
    }

    private List<Draft> createDrafts(
            InstitutionPortfolioPolicy policy,
            List<InstitutionSymbolMandate> mandates,
            Map<String, Double> normalizedBaseWeights,
            double targetStockAllocation,
            Map<String, Double> targetAllocations,
            Map<String, BlendedPressure> blendedPressures,
            Map<String, InstitutionMarketInput> marketInputs,
            Map<String, InstitutionPositionSnapshot> positions,
            Map<String, InstitutionDailyBudgetSnapshot> budgets,
            Map<String, InstitutionDecisionAction> previousActions,
            BigDecimal liquidAssetAmount
    ) {
        List<Draft> drafts = new ArrayList<>();
        for (InstitutionSymbolMandate mandate : mandates) {
            String symbol = mandate.symbol();
            InstitutionMarketInput input = marketInputs.get(symbol);
            InstitutionPositionSnapshot position = positions.getOrDefault(
                    symbol,
                    InstitutionPositionSnapshot.EMPTY
            );
            InstitutionDailyBudgetSnapshot budget = budgets.get(symbol);
            long projectedQuantity = position.projectedQuantity();
            BigDecimal actualAmount = money(input.currentPrice().multiply(BigDecimal.valueOf(position.actualQuantity())));
            BigDecimal projectedAmount = money(
                    input.currentPrice().multiply(BigDecimal.valueOf(projectedQuantity))
            );
            BigDecimal actualAllocation = allocationRate(actualAmount, liquidAssetAmount);
            BigDecimal projectedAllocation = allocationRate(projectedAmount, liquidAssetAmount);
            BigDecimal baseAllocation = rate(
                    policy.baseStockAllocationRate().doubleValue() * normalizedBaseWeights.get(symbol)
            );
            BigDecimal targetAllocation = rate(targetAllocations.get(symbol));
            BigDecimal targetAmount = money(liquidAssetAmount.multiply(targetAllocation));
            BigDecimal signedDifference = targetAmount.subtract(projectedAmount);
            InstitutionDecisionAction previousAction = previousActions.getOrDefault(
                    symbol,
                    InstitutionDecisionAction.HOLD
            );
            DecisionDirection direction = decideDirection(
                    policy,
                    signedDifference,
                    projectedAllocation,
                    targetAllocation,
                    previousAction,
                    liquidAssetAmount,
                    input.currentPrice()
            );
            BigDecimal rawTradeAmount = direction.action() == InstitutionDecisionAction.HOLD
                    ? BigDecimal.ZERO
                    : money(signedDifference.abs());
            long rawQuantity = input.currentPrice().signum() <= 0
                    ? 0L
                    : floorToLong(rawTradeAmount.divide(input.currentPrice(), 0, RoundingMode.DOWN));
            long remainingQuantity = budget.remainingQuantity();
            long individuallyGatedQuantity = Math.min(rawQuantity, remainingQuantity);
            EnumSet<Gate> gates = EnumSet.noneOf(Gate.class);
            if (direction.action() == InstitutionDecisionAction.HOLD) {
                gates.add(Gate.HOLD);
                individuallyGatedQuantity = 0L;
            } else if (rawQuantity == 0L) {
                gates.add(Gate.MINIMUM_LOT);
            }
            if (rawQuantity > remainingQuantity) {
                gates.add(Gate.SYMBOL_PARTICIPATION_LIMIT);
            }
            long singleDecisionLimit = Math.max(
                    1L,
                    floorToLong(
                            BigDecimal.valueOf(mandate.referenceDailyVolume())
                                    .multiply(LIVE_SINGLE_DECISION_REFERENCE_RATE)
                    )
            );
            if (individuallyGatedQuantity > singleDecisionLimit) {
                individuallyGatedQuantity = singleDecisionLimit;
                gates.add(Gate.SINGLE_ORDER_LIMIT);
            }
            if (direction.action() == InstitutionDecisionAction.SELL) {
                long availableSellQuantity = position.availableSellQuantity();
                if (individuallyGatedQuantity > availableSellQuantity) {
                    individuallyGatedQuantity = availableSellQuantity;
                    gates.add(Gate.SHARE_LIMIT);
                }
            }
            drafts.add(new Draft(
                    mandate,
                    input,
                    position,
                    budget,
                    projectedQuantity,
                    blendedPressures.get(symbol),
                    actualAllocation,
                    projectedAllocation,
                    baseAllocation,
                    targetAllocation,
                    targetAmount,
                    rawTradeAmount,
                    rawQuantity,
                    individuallyGatedQuantity,
                    direction.action(),
                    direction.reason(),
                    gates
            ));
        }
        return drafts;
    }

    private DecisionDirection decideDirection(
            InstitutionPortfolioPolicy policy,
            BigDecimal signedDifference,
            BigDecimal projectedAllocation,
            BigDecimal targetAllocation,
            InstitutionDecisionAction previousAction,
            BigDecimal liquidAssetAmount,
            BigDecimal currentPrice
    ) {
        if (liquidAssetAmount.signum() <= 0) {
            return new DecisionDirection(InstitutionDecisionAction.HOLD, "NO_ASSET");
        }
        if (currentPrice.signum() <= 0) {
            return new DecisionDirection(InstitutionDecisionAction.HOLD, "NO_PRICE");
        }
        BigDecimal allocationDifference = targetAllocation.subtract(projectedAllocation);
        InstitutionDecisionAction direction = signedDifference.signum() > 0
                ? InstitutionDecisionAction.BUY
                : signedDifference.signum() < 0
                        ? InstitutionDecisionAction.SELL
                        : InstitutionDecisionAction.HOLD;
        BigDecimal threshold = direction != InstitutionDecisionAction.HOLD && direction == previousAction
                ? policy.exitThresholdRate()
                : policy.entryThresholdRate();
        if (direction == InstitutionDecisionAction.HOLD
                || allocationDifference.abs().compareTo(threshold) <= 0) {
            return new DecisionDirection(InstitutionDecisionAction.HOLD, "HYSTERESIS_BAND");
        }
        return direction == InstitutionDecisionAction.BUY
                ? new DecisionDirection(direction, "TARGET_DEFICIT")
                : new DecisionDirection(direction, "TARGET_SURPLUS");
    }

    private void applySharedNotionalLimit(
            List<Draft> drafts,
            BigDecimal sharedLimit,
            BigDecimal dailyLimit,
            BigDecimal decisionLimit
    ) {
        BigDecimal total = drafts.stream().map(Draft::gatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0 || total.compareTo(sharedLimit) <= 0) {
            return;
        }
        BigDecimal ratio = sharedLimit.divide(total, 18, RoundingMode.DOWN);
        Gate bindingGate = dailyLimit.compareTo(decisionLimit) <= 0
                ? Gate.PORTFOLIO_DAILY_LIMIT
                : Gate.DECISION_LIMIT;
        List<Long> originalQuantities = drafts.stream()
                .map(Draft::gatedQuantity)
                .toList();
        for (Draft draft : drafts) {
            if (draft.gatedQuantity() <= 0L) {
                continue;
            }
            long scaledQuantity = Math.min(
                    draft.gatedQuantity(),
                    floorToLong(BigDecimal.valueOf(draft.gatedQuantity()).multiply(ratio))
            );
            if (scaledQuantity < draft.gatedQuantity()) {
                draft.setGatedQuantity(scaledQuantity);
                draft.gates().add(bindingGate);
            }
        }
        fillResidualCapacity(
                drafts,
                originalQuantities,
                sharedLimit,
                null
        );
    }

    private void applyCashLimit(List<Draft> drafts, BigDecimal availableCash) {
        BigDecimal totalBuy = drafts.stream()
                .filter(draft -> draft.action() == InstitutionDecisionAction.BUY)
                .map(Draft::gatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalBuy.signum() <= 0 || totalBuy.compareTo(availableCash) <= 0) {
            return;
        }
        BigDecimal ratio = availableCash.divide(totalBuy, 18, RoundingMode.DOWN);
        List<Long> originalQuantities = drafts.stream()
                .map(Draft::gatedQuantity)
                .toList();
        for (Draft draft : drafts) {
            if (draft.action() != InstitutionDecisionAction.BUY || draft.gatedQuantity() <= 0L) {
                continue;
            }
            long scaledQuantity = Math.min(
                    draft.gatedQuantity(),
                    floorToLong(BigDecimal.valueOf(draft.gatedQuantity()).multiply(ratio))
            );
            if (scaledQuantity < draft.gatedQuantity()) {
                draft.setGatedQuantity(scaledQuantity);
                draft.gates().add(Gate.CASH_LIMIT);
            }
        }
        fillResidualCapacity(
                drafts,
                originalQuantities,
                availableCash,
                InstitutionDecisionAction.BUY
        );
    }

    private void fillResidualCapacity(
            List<Draft> drafts,
            List<Long> originalQuantities,
            BigDecimal notionalLimit,
            InstitutionDecisionAction actionFilter
    ) {
        BigDecimal used = drafts.stream()
                .filter(draft -> actionFilter == null || draft.action() == actionFilter)
                .map(Draft::gatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = nonNegative(notionalLimit.subtract(used));
        for (int index = 0; index < drafts.size() && remaining.signum() > 0; index++) {
            Draft draft = drafts.get(index);
            if (actionFilter != null && draft.action() != actionFilter) {
                continue;
            }
            long availableQuantity = Math.max(
                    0L,
                    originalQuantities.get(index) - draft.gatedQuantity()
            );
            if (availableQuantity == 0L || draft.input.currentPrice().signum() <= 0) {
                continue;
            }
            long affordableQuantity = floorToLong(
                    remaining.divide(
                            draft.input.currentPrice(),
                            18,
                            RoundingMode.DOWN
                    )
            );
            long restoredQuantity = Math.min(availableQuantity, affordableQuantity);
            if (restoredQuantity <= 0L) {
                continue;
            }
            draft.setGatedQuantity(draft.gatedQuantity() + restoredQuantity);
            remaining = nonNegative(
                    remaining.subtract(
                            draft.input.currentPrice()
                                    .multiply(BigDecimal.valueOf(restoredQuantity))
                    )
            );
        }
    }

    private void finalizeQuantities(List<Draft> drafts) {
        for (Draft draft : drafts) {
            draft.setGatedQuantity(Math.max(0L, draft.gatedQuantity()));
            if (draft.action() != InstitutionDecisionAction.HOLD
                    && draft.rawQuantity() > 0L
                    && draft.gatedQuantity() == 0L
                    && draft.gates().isEmpty()) {
                draft.gates().add(Gate.ROUNDING);
            }
        }
    }

    private double blend(int primary, int secondary, double primaryWeight) {
        return Math.clamp(
                primary / 100.0 * primaryWeight + secondary / 100.0 * (1.0 - primaryWeight),
                -1.0,
                1.0
        );
    }

    private BigDecimal allocationRate(BigDecimal amount, BigDecimal liquidAssetAmount) {
        if (liquidAssetAmount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        return amount.divide(liquidAssetAmount, RATE_SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
    }

    private static BigDecimal rate(double value) {
        return BigDecimal.valueOf(Math.max(0.0, value)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal historicalReturn(double value) {
        double finite = Double.isFinite(value) ? value : 0.0;
        return BigDecimal.valueOf(Math.clamp(finite, -1.0, 10.0))
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal pressure(double value) {
        return BigDecimal.valueOf(Math.clamp(value, -1.0, 1.0))
                .setScale(PRESSURE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return nonNegative(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal moneyDown(BigDecimal value) {
        return nonNegative(value).setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private static long floorToLong(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return 0L;
        }
        BigDecimal maximum = BigDecimal.valueOf(Long.MAX_VALUE);
        if (value.compareTo(maximum) >= 0) {
            return Long.MAX_VALUE;
        }
        return value.setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private enum Gate {
        HOLD,
        MINIMUM_LOT,
        SYMBOL_PARTICIPATION_LIMIT,
        SINGLE_ORDER_LIMIT,
        SHARE_LIMIT,
        PORTFOLIO_DAILY_LIMIT,
        DECISION_LIMIT,
        CASH_LIMIT,
        ROUNDING
    }

    private record BlendedPressure(
            double price,
            double assetPreference,
            double volatility,
            double liquidity,
            double executionAggression
    ) {
    }

    private record DecisionDirection(InstitutionDecisionAction action, String reason) {
    }

    private static final class Draft {

        private final InstitutionSymbolMandate mandate;
        private final InstitutionMarketInput input;
        private final InstitutionPositionSnapshot position;
        private final InstitutionDailyBudgetSnapshot budget;
        private final long projectedQuantity;
        private final BlendedPressure pressure;
        private final BigDecimal actualAllocation;
        private final BigDecimal projectedAllocation;
        private final BigDecimal baseAllocation;
        private final BigDecimal targetAllocation;
        private final BigDecimal targetAmount;
        private final BigDecimal rawTradeAmount;
        private final long rawQuantity;
        private long gatedQuantity;
        private final InstitutionDecisionAction action;
        private final String decisionReason;
        private final EnumSet<Gate> gates;

        private Draft(
                InstitutionSymbolMandate mandate,
                InstitutionMarketInput input,
                InstitutionPositionSnapshot position,
                InstitutionDailyBudgetSnapshot budget,
                long projectedQuantity,
                BlendedPressure pressure,
                BigDecimal actualAllocation,
                BigDecimal projectedAllocation,
                BigDecimal baseAllocation,
                BigDecimal targetAllocation,
                BigDecimal targetAmount,
                BigDecimal rawTradeAmount,
                long rawQuantity,
                long gatedQuantity,
                InstitutionDecisionAction action,
                String decisionReason,
                EnumSet<Gate> gates
        ) {
            this.mandate = mandate;
            this.input = input;
            this.position = position;
            this.budget = budget;
            this.projectedQuantity = projectedQuantity;
            this.pressure = pressure;
            this.actualAllocation = actualAllocation;
            this.projectedAllocation = projectedAllocation;
            this.baseAllocation = baseAllocation;
            this.targetAllocation = targetAllocation;
            this.targetAmount = targetAmount;
            this.rawTradeAmount = rawTradeAmount;
            this.rawQuantity = rawQuantity;
            this.gatedQuantity = gatedQuantity;
            this.action = action;
            this.decisionReason = decisionReason;
            this.gates = gates;
        }

        private long gatedQuantity() {
            return gatedQuantity;
        }

        private void setGatedQuantity(long quantity) {
            gatedQuantity = quantity;
        }

        private long rawQuantity() {
            return rawQuantity;
        }

        private InstitutionDecisionAction action() {
            return action;
        }

        private EnumSet<Gate> gates() {
            return gates;
        }

        private BigDecimal gatedAmount() {
            return money(input.currentPrice().multiply(BigDecimal.valueOf(gatedQuantity)));
        }

        private InstitutionDecisionItem toDecisionItem(
                BigDecimal liquidAssetAmount,
                double targetStockAllocation,
                BigDecimal remainingDailyNotionalAfter
        ) {
            long remainingQuantityAfter = Math.max(0L, budget.remainingQuantity() - gatedQuantity);
            String gateReason = gates.isEmpty()
                    ? "PASSED"
                    : gates.stream().map(Enum::name).collect(Collectors.joining(","));
            AutoMarketPressure primary = input.primaryPressure();
            AutoMarketPressure secondary = input.secondaryPressure();
            return new InstitutionDecisionItem(
                    mandate.symbol(),
                    primary,
                    secondary,
                    pressure(pressure.price()),
                    pressure(pressure.assetPreference()),
                    pressure(pressure.volatility()),
                    pressure(pressure.liquidity()),
                    pressure(pressure.executionAggression()),
                    historicalReturn(input.return5Day()),
                    historicalReturn(input.return20Day()),
                    pressure(input.reportPressure()),
                    money(input.currentPrice()),
                    liquidAssetAmount,
                    position.actualQuantity(),
                    position.openBuyQuantity(),
                    position.openSellQuantity(),
                    projectedQuantity,
                    actualAllocation,
                    projectedAllocation,
                    baseAllocation,
                    rate(targetStockAllocation),
                    targetAllocation,
                    targetAmount,
                    rawTradeAmount,
                    gatedAmount(),
                    gatedQuantity,
                    action,
                    decisionReason,
                    gateReason,
                    budget.referenceDailyVolume(),
                    budget.grossQuantityLimit(),
                    budget.grossNotionalLimit(),
                    remainingQuantityAfter,
                    remainingDailyNotionalAfter
            );
        }
    }
}
