package stock.batch.service.batch.settlement.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;

@Component
public class PortfolioSnapshotProcessor {

    public PortfolioSnapshotCommand process(AccountSettlementTarget target) {
        BigDecimal totalAsset = target.cashBalance()
                .add(target.reservedBuyCash())
                .add(target.marketValue());
        BigDecimal returnRate = BigDecimal.ZERO;
        if (target.netCashFlow().compareTo(BigDecimal.ZERO) > 0) {
            returnRate = totalAsset.subtract(target.netCashFlow())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(target.netCashFlow(), 4, RoundingMode.HALF_UP);
        }
        return new PortfolioSnapshotCommand(
                target.accountId(),
                target.userKey(),
                target.cashBalance(),
                target.marketValue(),
                totalAsset,
                returnRate
        );
    }
}
