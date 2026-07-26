package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@RequiredArgsConstructor
class LiquidityProviderQuoteProcessor {

    private final LiquidityProviderRepository repository;
    private final LiquidityProviderQuotePlanner quotePlanner;
    private final AutoMarketOrderExecutor orderExecutor;

    ProcessResult process(
            long mandateId,
            AutoMarketConfig marketConfig,
            boolean marketTradingEnabled,
            LocalDate simulationTradeDate,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Liquidity-provider quote processing requires an active transaction");
        }
        LocalDateTime now = sessionApproval.businessEffectiveAt();
        LiquidityProviderMandate mandate = repository.lockMandate(mandateId).orElse(null);
        if (mandate == null || isNotDue(mandate, now)) {
            return ProcessResult.SKIPPED;
        }
        if (!sessionApproval.sessionEpochs().containsKey(mandate.symbol())) {
            throw new IllegalArgumentException(
                    "Liquidity mandate symbol is not covered by the held market session fence: "
                            + mandate.symbol()
            );
        }
        LiquidityProviderAccountSnapshot account = repository.lockAccountSnapshot(
                mandate,
                simulationTradeDate
        );
        LiquidityProviderRepository.OpenOrderLoad openOrders = repository.findOpenOrders(mandate);
        LiquidityProviderExecutionSnapshot executions = repository.findExecutionSnapshot(
                mandate,
                simulationTradeDate
        );
        LiquidityProviderDailyState dailyState = repository.lockDailyState(
                simulationTradeDate,
                mandate.id()
        );
        LiquidityProviderExternalBook externalBook = repository.findExternalBook(
                mandate,
                account.accountSelfTradeGroupId()
        );
        LiquidityProviderQuoteInput input = new LiquidityProviderQuoteInput(
                mandate,
                marketConfig,
                simulationTradeDate,
                now,
                account,
                executions,
                dailyState,
                externalBook,
                openOrders.orders(),
                openOrders.overflow(),
                marketTradingEnabled
        );
        LiquidityProviderQuotePlan plan = quotePlanner.plan(input);

        int cancelledOrderCount = orderExecutor.expireOrders(plan.cancellationOrders(), now);
        if (cancelledOrderCount != plan.cancellationOrders().size()) {
            throw new IllegalStateException(
                    "Liquidity-provider cancellation count mismatch: mandateId=%d, expected=%d, actual=%d"
                            .formatted(
                                    mandate.id(),
                                    plan.cancellationOrders().size(),
                                    cancelledOrderCount
                            )
            );
        }

        int generatedOrderCount = 0;
        if (!plan.executableOrders().isEmpty()) {
            AutoParticipantOrderGenerationResult generationResult =
                    orderExecutor.placeOrdersWithOpenFenceHeld(
                            plan.executableOrders(),
                            sessionApproval
                    );
            generatedOrderCount = generationResult.generatedOrderCount();
            if (generatedOrderCount != plan.executableOrders().size()) {
                throw new IllegalStateException(
                        "Liquidity-provider order generation count mismatch: mandateId=%d, expected=%d, actual=%d, dropped=%s"
                                .formatted(
                                        mandate.id(),
                                        plan.executableOrders().size(),
                                        generatedOrderCount,
                                        generationResult.droppedOrderCounts()
                                )
                );
            }
        }

        repository.persistDailyState(input, plan, now);
        repository.advanceNextQuoteAt(mandate, now);
        return new ProcessResult(
                true,
                cancelledOrderCount,
                generatedOrderCount,
                plan.stateStatus(),
                plan.gateReason()
        );
    }

    private boolean isNotDue(LiquidityProviderMandate mandate, LocalDateTime now) {
        return mandate.nextQuoteAt() != null && mandate.nextQuoteAt().isAfter(now);
    }

    record ProcessResult(
            boolean processed,
            int cancelledOrderCount,
            int generatedOrderCount,
            String stateStatus,
            String gateReason
    ) {
        static final ProcessResult SKIPPED =
                new ProcessResult(false, 0, 0, "SKIPPED", "NOT_DUE");

        int mutationCount() {
            return cancelledOrderCount + generatedOrderCount;
        }
    }
}
