package stock.batch.service.batch.settlement.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioReturnRateStatus;
import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;

@Component
public class PortfolioSnapshotProcessor {

    public static final String CALCULATION_VERSION = "portfolio-v5-net-contribution-return";

    public PortfolioSnapshotCommand process(AccountSettlementTarget target) {
        BigDecimal totalAsset = target.cashBalance()
                .add(target.pendingSubscriptionAsset())
                .add(target.marketValue());
        BigDecimal totalProfit = totalAsset.subtract(target.netContribution());
        PortfolioReturnRateStatus returnRateStatus = PortfolioReturnRateStatus.from(target.netContribution());
        BigDecimal returnRate = null;
        if (returnRateStatus == PortfolioReturnRateStatus.DEFINED) {
            returnRate = totalProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(target.netContribution(), 8, RoundingMode.HALF_UP);
        }
        return new PortfolioSnapshotCommand(
                target.closeCycleId(),
                target.closeRunId(),
                target.accountId(),
                target.userKey(),
                target.cashBalance(),
                target.pendingSubscriptionAsset(),
                target.marketValue(),
                target.holdingQuantity(),
                target.reservedSellQuantity(),
                target.holdingPositionCount(),
                totalAsset,
                target.netContribution(),
                totalProfit,
                returnRate,
                returnRateStatus,
                inputHash(target),
                CALCULATION_VERSION,
                "VERIFIED"
        );
    }

    public String inputHash(AccountSettlementTarget target) {
        String input = String.join(
                "|",
                Long.toString(target.closeCycleId()),
                Long.toString(target.closeRunId()),
                Long.toString(target.accountId()),
                decimal(target.cashBalance()),
                decimal(target.netContribution()),
                decimal(target.marketValue()),
                decimal(target.pendingSubscriptionAsset()),
                Long.toString(target.holdingQuantity()),
                Long.toString(target.reservedSellQuantity()),
                Long.toString(target.holdingPositionCount())
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
