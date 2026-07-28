package stock.batch.service.automarket.v3;

public enum SafeQuantityBindingReason {
    SYMBOL_MAX,
    OPEN_ORDER_ALLOWANCE,
    BUY_AFFORDABILITY,
    SELLABLE_HOLDING,
    ALLOCATION,
    FUNDING_BUDGET,
    OPPOSITE_DEPTH,
    AVERAGE_DAILY_VOLUME,
    EMERGENCY_DAILY_TURNOVER
}
