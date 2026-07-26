package stock.batch.service.automarket.biz;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.StockOrderOriginType;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@RequiredArgsConstructor
class InstitutionOrderIntentProcessor {

    private final InstitutionOrderIntentRepository repository;
    private final InstitutionOrderExecutionPlanner executionPlanner;
    private final AutoMarketOrderExecutor orderExecutor;

    ProcessResult process(
            InstitutionOrderIntentRepository.IntentReference reference,
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Institution order-intent processing requires an active transaction"
            );
        }
        InstitutionOrderIntent intent = repository.lockIntent(
                reference.decisionRunId(),
                reference.symbol(),
                simulationTradeDate
        ).orElse(null);
        if (intent == null) {
            return ProcessResult.SKIPPED;
        }
        if (!sessionApproval.sessionEpochs().containsKey(intent.symbol())) {
            throw new IllegalArgumentException(
                    "Institution intent symbol is not covered by the held market session fence"
            );
        }
        InstitutionExternalBook externalBook = repository.findExternalBook(intent);
        InstitutionOrderExecutionPlan plan = executionPlanner.plan(
                intent,
                config,
                externalBook,
                sessionApproval.businessEffectiveAt()
        );
        if (!plan.executable()) {
            repository.markRejected(
                    intent,
                    plan.reason(),
                    sessionApproval.businessEffectiveAt()
            );
            return new ProcessResult(true, false, plan.reason());
        }
        AutoMarketPlannedOrder order = new AutoMarketPlannedOrder(
                intent.accountId(),
                intent.symbol(),
                intent.side(),
                plan.price(),
                plan.quantity(),
                null,
                null,
                plan.expiresAt(),
                null,
                null,
                StockOrderOriginType.INSTITUTIONAL_INVESTOR,
                AutoMarketOrderStrategyOrigin.institution(
                        intent.participantId(),
                        intent.portfolioId(),
                        intent.decisionRunId(),
                        intent.policyVersion()
                )
        );
        AutoParticipantOrderGenerationResult generationResult =
                orderExecutor.placeOrdersWithOpenFenceHeld(
                        java.util.List.of(order),
                        sessionApproval
                );
        if (generationResult.generatedOrderCount() != 1) {
            String reason = "ORDER_RESERVATION_REJECTED:" + generationResult.droppedOrderCounts();
            repository.markRejected(
                    intent,
                    reason,
                    sessionApproval.businessEffectiveAt()
            );
            return new ProcessResult(true, false, reason);
        }
        repository.markSubmitted(
                intent,
                plan,
                simulationTradeDate,
                sessionApproval.businessEffectiveAt()
        );
        return new ProcessResult(true, true, plan.reason());
    }

    record ProcessResult(boolean processed, boolean submitted, String reason) {
        static final ProcessResult SKIPPED = new ProcessResult(false, false, "NOT_PENDING");
    }
}
