package stock.batch.service.automarket.biz;

enum AutoMarketOrderDropReason {
    SIDE_NOT_SELECTED("side_not_selected"),
    OPEN_QUANTITY_LIMIT("open_quantity_limit"),
    QUANTITY_MULTIPLIER_ZERO("quantity_multiplier_zero"),
    INVALID_PRICE("invalid_price"),
    INSUFFICIENT_CASH("insufficient_cash"),
    INSUFFICIENT_HOLDING("insufficient_holding"),
    PROFILE_RISK_LIMIT("profile_risk_limit"),
    FUNDING_BUDGET_EMPTY("funding_budget_empty"),
    FUNDING_BUDGET_RESERVATION_FAILED("funding_budget_reservation_failed"),
    SESSION_CLOSED("session_closed"),
    BUY_RESERVATION_FAILED("buy_reservation_failed"),
    SELL_RESERVATION_FAILED("sell_reservation_failed");

    private final String metricTag;

    AutoMarketOrderDropReason(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
