package stock.batch.service.automarket.biz;

import stock.batch.service.batch.automarket.model.StockOrderOriginType;

record AutoMarketOrderStrategyOrigin(
        StockOrderOriginType originType,
        Long participantId,
        Long portfolioId,
        Long decisionRunId,
        Long liquidityMandateId,
        Long underwritingContractId,
        long policyVersion
) {

    AutoMarketOrderStrategyOrigin {
        if (originType != StockOrderOriginType.INSTITUTIONAL_INVESTOR
                && originType != StockOrderOriginType.LIQUIDITY_PROVIDER
                && originType != StockOrderOriginType.ISSUE_UNDERWRITER) {
            throw new IllegalArgumentException(
                    "Strategy-origin metadata is only supported for institutional market roles"
            );
        }
        if (participantId == null || participantId <= 0L || policyVersion <= 0L) {
            throw new IllegalArgumentException(
                    "Strategy-origin participant and policy version must be positive"
            );
        }
        if (originType == StockOrderOriginType.INSTITUTIONAL_INVESTOR
                && (portfolioId == null || portfolioId <= 0L
                || decisionRunId == null || decisionRunId <= 0L
                || liquidityMandateId != null
                || underwritingContractId != null)) {
            throw new IllegalArgumentException(
                    "Institutional order origin requires portfolio and decision-run ownership"
            );
        }
        if (originType == StockOrderOriginType.LIQUIDITY_PROVIDER
                && (liquidityMandateId == null || liquidityMandateId <= 0L
                || portfolioId != null || decisionRunId != null
                || underwritingContractId != null)) {
            throw new IllegalArgumentException(
                    "Liquidity-provider order origin requires one mandate owner"
            );
        }
        if (originType == StockOrderOriginType.ISSUE_UNDERWRITER
                && (underwritingContractId == null || underwritingContractId <= 0L
                || portfolioId != null || decisionRunId != null
                || liquidityMandateId != null)) {
            throw new IllegalArgumentException(
                    "Issue-underwriter order origin requires one underwriting contract owner"
            );
        }
    }

    static AutoMarketOrderStrategyOrigin institution(
            long participantId,
            long portfolioId,
            long decisionRunId,
            long policyVersion
    ) {
        return new AutoMarketOrderStrategyOrigin(
                StockOrderOriginType.INSTITUTIONAL_INVESTOR,
                participantId,
                portfolioId,
                decisionRunId,
                null,
                null,
                policyVersion
        );
    }

    static AutoMarketOrderStrategyOrigin liquidityProvider(
            long participantId,
            long mandateId,
            long policyVersion
    ) {
        return new AutoMarketOrderStrategyOrigin(
                StockOrderOriginType.LIQUIDITY_PROVIDER,
                participantId,
                null,
                null,
                mandateId,
                null,
                policyVersion
        );
    }

    static AutoMarketOrderStrategyOrigin issueUnderwriter(
            long participantId,
            long underwritingContractId,
            long policyVersion
    ) {
        return new AutoMarketOrderStrategyOrigin(
                StockOrderOriginType.ISSUE_UNDERWRITER,
                participantId,
                null,
                null,
                null,
                underwritingContractId,
                policyVersion
        );
    }
}
