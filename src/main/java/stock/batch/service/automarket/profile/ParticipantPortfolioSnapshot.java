package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

public record ParticipantPortfolioSnapshot(
        long holdingQuantity,
        long reservedQuantity,
        BigDecimal openBuyReservedCash,
        BigDecimal holdingMarketValue,
        BigDecimal liquidAsset,
        int positionCount,
        long symbolOpenBuyQuantity,
        int eligibleSymbolCount
) {
    public static final ParticipantPortfolioSnapshot EMPTY = new ParticipantPortfolioSnapshot(
            0L,
            0L,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            0L,
            1
    );

    public ParticipantPortfolioSnapshot(
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal openBuyReservedCash,
            BigDecimal holdingMarketValue,
            BigDecimal liquidAsset,
            int positionCount
    ) {
        this(
                holdingQuantity,
                reservedQuantity,
                openBuyReservedCash,
                holdingMarketValue,
                liquidAsset,
                positionCount,
                0L,
                1
        );
    }

    public ParticipantPortfolioSnapshot(
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal openBuyReservedCash,
            BigDecimal holdingMarketValue,
            BigDecimal liquidAsset,
            int positionCount,
            long symbolOpenBuyQuantity
    ) {
        this(
                holdingQuantity,
                reservedQuantity,
                openBuyReservedCash,
                holdingMarketValue,
                liquidAsset,
                positionCount,
                symbolOpenBuyQuantity,
                1
        );
    }

    public ParticipantPortfolioSnapshot {
        holdingQuantity = Math.max(0L, holdingQuantity);
        reservedQuantity = Math.max(0L, reservedQuantity);
        openBuyReservedCash = zeroIfNull(openBuyReservedCash);
        holdingMarketValue = zeroIfNull(holdingMarketValue);
        liquidAsset = zeroIfNull(liquidAsset);
        positionCount = Math.max(0, positionCount);
        symbolOpenBuyQuantity = Math.max(0L, symbolOpenBuyQuantity);
        eligibleSymbolCount = Math.max(1, eligibleSymbolCount);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
