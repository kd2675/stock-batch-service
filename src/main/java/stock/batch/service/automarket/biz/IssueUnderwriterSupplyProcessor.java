package stock.batch.service.automarket.biz;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@RequiredArgsConstructor
class IssueUnderwriterSupplyProcessor {

    private final IssueUnderwriterSupplyRepository repository;
    private final IssueUnderwriterSupplyPlanner planner;
    private final AutoMarketOrderExecutor orderExecutor;

    ProcessResult process(
            long contractId,
            AutoMarketConfig marketConfig,
            boolean marketTradingEnabled,
            LocalDate simulationTradeDate,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Issue-underwriter supply processing requires an active transaction"
            );
        }
        IssueUnderwriterSupplyRepository.ContractSnapshot contract =
                repository.lockContract(contractId).orElse(null);
        if (contract == null) {
            return ProcessResult.SKIPPED;
        }
        if (!sessionApproval.sessionEpochs().containsKey(contract.symbol())) {
            throw new IllegalArgumentException(
                    "Underwriting contract symbol is not covered by the market session fence"
            );
        }
        IssueUnderwriterSupplyRepository.AccountSnapshot account =
                repository.lockAccountSnapshot(contract, simulationTradeDate);
        IssueUnderwriterSupplyRepository.OpenOrderLoad openOrders =
                repository.findOpenOrders(contract);
        long referenceDailyVolume = repository.findReferenceDailyVolume(contract.symbol());
        IssueUnderwriterSupplyRepository.DailyState dailyState =
                repository.lockDailyState(contract.id(), simulationTradeDate);
        IssueUnderwriterSupplyRepository.SupplyUsage supplyUsage =
                repository.findSupplyUsage(contract.id());
        IssueUnderwriterSupplyRepository.ExternalBook externalBook =
                repository.findExternalBook(
                        contract,
                        account.accountSelfTradeGroupId()
                );
        IssueUnderwriterSupplyPlanner.SupplyInput input =
                new IssueUnderwriterSupplyPlanner.SupplyInput(
                        contract,
                        marketConfig,
                        marketTradingEnabled,
                        sessionApproval.businessEffectiveAt(),
                        simulationTradeDate,
                        account,
                        openOrders,
                        externalBook,
                        referenceDailyVolume,
                        dailyState,
                        supplyUsage
                );
        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(input);

        int cancelledOrderCount = orderExecutor.expireOrders(
                plan.cancellationOrders(),
                sessionApproval.businessEffectiveAt()
        );
        if (cancelledOrderCount != plan.cancellationOrders().size()) {
            throw new IllegalStateException(
                    "Issue-underwriter cancellation count mismatch: contractId=%d, expected=%d, actual=%d"
                            .formatted(
                                    contract.id(),
                                    plan.cancellationOrders().size(),
                                    cancelledOrderCount
                            )
            );
        }

        int generatedOrderCount = 0;
        if (!plan.executableOrders().isEmpty()) {
            AutoParticipantOrderGenerationResult result =
                    orderExecutor.placeOrdersWithOpenFenceHeld(
                            plan.executableOrders(),
                            sessionApproval
                    );
            generatedOrderCount = result.generatedOrderCount();
            if (generatedOrderCount != plan.executableOrders().size()) {
                throw new IllegalStateException(
                        "Issue-underwriter order generation count mismatch: contractId=%d, expected=%d, actual=%d, dropped=%s"
                                .formatted(
                                        contract.id(),
                                        plan.executableOrders().size(),
                                        generatedOrderCount,
                                        result.droppedOrderCounts()
                                )
                );
            }
        }

        repository.persistDailyState(
                contract,
                dailyState,
                plan,
                generatedOrderCount,
                cancelledOrderCount,
                sessionApproval.businessEffectiveAt()
        );
        if (plan.completeContract()) {
            repository.completeContract(
                    contract,
                    sessionApproval.businessEffectiveAt()
            );
        }
        return new ProcessResult(
                true,
                cancelledOrderCount,
                generatedOrderCount,
                plan.stateStatus(),
                plan.gateReason()
        );
    }

    record ProcessResult(
            boolean processed,
            int cancelledOrderCount,
            int generatedOrderCount,
            String stateStatus,
            String gateReason
    ) {
        static final ProcessResult SKIPPED =
                new ProcessResult(false, 0, 0, "SKIPPED", "CONTRACT_MISSING");
    }
}
