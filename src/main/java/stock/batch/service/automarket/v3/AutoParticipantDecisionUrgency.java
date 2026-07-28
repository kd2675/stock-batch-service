package stock.batch.service.automarket.v3;

public enum AutoParticipantDecisionUrgency {
    VOLUNTARY,
    RISK_REDUCTION,
    MANDATORY_CLOSE,
    OPERATIONAL_QUOTE;

    public boolean bypassesAttentionGate() {
        return this == RISK_REDUCTION || this == MANDATORY_CLOSE;
    }
}
