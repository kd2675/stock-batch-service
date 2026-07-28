package stock.batch.service.automarket.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record AutoParticipantV3Policy(
        long policyVersion,
        double executionIntercept,
        double signalSensitivity,
        double fatigueSensitivity,
        double fatigueHalfLifeSeconds,
        double reentryTauSeconds,
        double ordinaryQuantityGamma,
        double rareLargeOrderProbability
) {

    public static AutoParticipantV3Policy defaults(long policyVersion) {
        return new AutoParticipantV3Policy(
                policyVersion,
                -0.35,
                1.70,
                1.15,
                2_700.0,
                180.0,
                3.0,
                0.025
        );
    }

    public static AutoParticipantV3Policy fromJson(
            long policyVersion,
            String policyJson,
            ObjectMapper objectMapper
    ) {
        try {
            JsonNode root = objectMapper.readTree(policyJson);
            if (!"V3".equals(root.path("model").asText())) {
                throw new IllegalArgumentException("Auto-participant policy model must be V3");
            }
            return new AutoParticipantV3Policy(
                    policyVersion,
                    requiredDouble(root, "executionIntercept"),
                    requiredDouble(root, "signalSensitivity"),
                    requiredDouble(root, "fatigueSensitivity"),
                    requiredDouble(root, "fatigueHalfLifeSeconds"),
                    requiredDouble(root, "reentryTauSeconds"),
                    requiredDouble(root, "ordinaryQuantityGamma"),
                    requiredDouble(root, "rareLargeOrderProbability")
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Auto-participant policy JSON is invalid", exception);
        }
    }

    public AutoParticipantV3Policy {
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("V3 policy version must be positive");
        }
        requireFinite(executionIntercept, "executionIntercept");
        requireFinite(signalSensitivity, "signalSensitivity");
        requireFinite(fatigueSensitivity, "fatigueSensitivity");
        requirePositive(fatigueHalfLifeSeconds, "fatigueHalfLifeSeconds");
        requirePositive(reentryTauSeconds, "reentryTauSeconds");
        requirePositive(ordinaryQuantityGamma, "ordinaryQuantityGamma");
        if (!Double.isFinite(rareLargeOrderProbability)
                || rareLargeOrderProbability < 0.0
                || rareLargeOrderProbability > 1.0) {
            throw new IllegalArgumentException("rareLargeOrderProbability must be between 0 and 1");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static double requiredDouble(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Auto-participant policy field is required: " + name);
        }
        return value.doubleValue();
    }
}
