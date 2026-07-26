package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record InstitutionExternalBook(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        long buyDepthQuantity,
        long sellDepthQuantity
) {

    static final InstitutionExternalBook EMPTY =
            new InstitutionExternalBook(null, null, 0L, 0L);
}
