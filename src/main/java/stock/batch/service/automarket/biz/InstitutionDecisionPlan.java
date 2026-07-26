package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.util.List;

record InstitutionDecisionPlan(
        BigDecimal liquidAssetAmount,
        BigDecimal targetStockAllocationRate,
        List<InstitutionDecisionItem> items
) {

    InstitutionDecisionPlan {
        items = List.copyOf(items);
    }
}
