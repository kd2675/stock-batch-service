package stock.batch.service.execution.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class AverageFillPriceCalculator {

    private AverageFillPriceCalculator() {
    }

    static BigDecimal calculate(
            BigDecimal previousAverageFillPrice,
            long previousFilledQuantity,
            long fillQuantity,
            BigDecimal executionPrice
    ) {
        BigDecimal previousAverage = previousAverageFillPrice == null ? BigDecimal.ZERO : previousAverageFillPrice;
        BigDecimal previousAmount = previousAverage.multiply(BigDecimal.valueOf(previousFilledQuantity));
        BigDecimal nextAmount = previousAmount.add(executionPrice.multiply(BigDecimal.valueOf(fillQuantity)));
        long nextFilledQuantity = previousFilledQuantity + fillQuantity;
        return nextAmount.divide(BigDecimal.valueOf(nextFilledQuantity), 2, RoundingMode.HALF_UP);
    }
}
