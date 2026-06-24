package stock.batch.service.execution.biz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ExecutionCostCalculator {

    private final BigDecimal feeRate;
    private final BigDecimal sellTaxRate;

    public ExecutionCostCalculator(
            @Value("${stock.batch.execution.fee-rate:0.0000}") BigDecimal feeRate,
            @Value("${stock.batch.execution.sell-tax-rate:0.0000}") BigDecimal sellTaxRate
    ) {
        this.feeRate = nonNegative(feeRate);
        this.sellTaxRate = nonNegative(sellTaxRate);
    }

    public ExecutionAmounts buy(long quantity, BigDecimal price) {
        BigDecimal grossAmount = grossAmount(quantity, price);
        BigDecimal feeAmount = rateAmount(grossAmount, feeRate);
        BigDecimal taxAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new ExecutionAmounts(
                grossAmount,
                feeAmount,
                taxAmount,
                grossAmount.add(feeAmount),
                null
        );
    }

    public ExecutionAmounts sell(long quantity, BigDecimal price, BigDecimal averagePrice) {
        BigDecimal grossAmount = grossAmount(quantity, price);
        BigDecimal feeAmount = rateAmount(grossAmount, feeRate);
        BigDecimal taxAmount = rateAmount(grossAmount, sellTaxRate);
        BigDecimal netAmount = grossAmount.subtract(feeAmount).subtract(taxAmount).max(BigDecimal.ZERO);
        BigDecimal costBasis = averagePrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        return new ExecutionAmounts(
                grossAmount,
                feeAmount,
                taxAmount,
                netAmount,
                netAmount.subtract(costBasis).setScale(2, RoundingMode.HALF_UP)
        );
    }

    private BigDecimal grossAmount(long quantity, BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rateAmount(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    public record ExecutionAmounts(
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal taxAmount,
            BigDecimal netAmount,
            BigDecimal realizedProfit
    ) {
    }
}
