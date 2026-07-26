package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoMarketHistoricalSignal;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;

record InstitutionMarketInput(
        String symbol,
        BigDecimal currentPrice,
        AutoMarketPressure primaryPressure,
        AutoMarketPressure secondaryPressure,
        double return5Day,
        double return20Day,
        double reportPressure
) {

    InstitutionMarketInput {
        currentPrice = currentPrice == null ? BigDecimal.ZERO : currentPrice.max(BigDecimal.ZERO);
        primaryPressure = primaryPressure == null ? AutoMarketPressure.NEUTRAL : primaryPressure;
        secondaryPressure = secondaryPressure == null ? AutoMarketPressure.NEUTRAL : secondaryPressure;
        return5Day = finiteClamped(return5Day, -1.0, 10.0);
        return20Day = finiteClamped(return20Day, -1.0, 10.0);
        reportPressure = finiteClamped(reportPressure, -1.0, 1.0);
    }

    static InstitutionMarketInput from(
            String symbol,
            BigDecimal currentPrice,
            AutoMarketPressure primaryPressure,
            AutoMarketPressure secondaryPressure,
            AutoMarketHistoricalSignal signal,
            double reportPressure
    ) {
        AutoMarketHistoricalSignal resolvedSignal = signal == null ? AutoMarketHistoricalSignal.EMPTY : signal;
        return new InstitutionMarketInput(
                symbol,
                currentPrice,
                primaryPressure,
                secondaryPressure,
                resolvedSignal.return5Day(),
                resolvedSignal.return20Day(),
                reportPressure
        );
    }

    private static double finiteClamped(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.clamp(value, minimum, maximum) : 0.0;
    }
}
