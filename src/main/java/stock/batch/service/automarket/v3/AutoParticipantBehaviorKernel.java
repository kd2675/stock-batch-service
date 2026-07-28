package stock.batch.service.automarket.v3;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

public final class AutoParticipantBehaviorKernel {

    private static final int SESSION_BUCKET_SECONDS = 300;
    private static final double MIN_POSITIVE_UNIT = 1.0e-12;

    public AutoParticipantActivityState sampleDailyState(
            AutoParticipantActivityState previousState,
            int intensity,
            long behaviorSeed,
            LocalDate tradeDate,
            AutoParticipantV3Policy policy
    ) {
        int normalizedIntensity = Math.clamp(intensity, 1, 10);
        double offline = Math.clamp(0.31 - normalizedIntensity * 0.025, 0.04, 0.285);
        double low = Math.clamp(0.43 - normalizedIntensity * 0.018, 0.20, 0.42);
        double normal = Math.clamp(0.22 + normalizedIntensity * 0.020, 0.24, 0.42);
        double high = Math.max(0.01, 1.0 - offline - low - normal);
        double[] stationary = normalize(new double[]{offline, low, normal, high});
        AutoParticipantActivityState previous = previousState == null
                ? AutoParticipantActivityState.NORMAL
                : previousState;
        double[] transition = new double[AutoParticipantActivityState.values().length];
        for (int index = 0; index < transition.length; index++) {
            transition[index] = stationary[index] * 0.45;
        }
        transition[previous.ordinal()] += 0.40;
        if (previous.ordinal() > 0) {
            transition[previous.ordinal() - 1] += 0.075;
        } else {
            transition[previous.ordinal()] += 0.075;
        }
        if (previous.ordinal() < transition.length - 1) {
            transition[previous.ordinal() + 1] += 0.075;
        } else {
            transition[previous.ordinal()] += 0.075;
        }
        SplittableRandom random = AutoParticipantV3Random.stream(
                behaviorSeed,
                tradeDate,
                policy.policyVersion(),
                0,
                AutoParticipantRandomStream.DAILY_STATE,
                previous.name()
        );
        return sampleCategorical(normalize(transition), random.nextDouble());
    }

    public LocalDateTime nextAttentionAt(
            LocalDateTime now,
            LocalDateTime sessionOpen,
            LocalDateTime sessionClose,
            AutoParticipantActivityState state,
            int intensity,
            long behaviorSeed,
            long eventSequence,
            AutoParticipantV3Policy policy
    ) {
        if (now == null || sessionOpen == null || sessionClose == null || !sessionOpen.isBefore(sessionClose)) {
            throw new IllegalArgumentException("A valid session window is required");
        }
        if (state == null || state == AutoParticipantActivityState.OFFLINE || !now.isBefore(sessionClose)) {
            return null;
        }
        LocalDateTime cursor = now.isBefore(sessionOpen) ? sessionOpen : now;
        SplittableRandom random = AutoParticipantV3Random.stream(
                behaviorSeed,
                sessionOpen.toLocalDate(),
                policy.policyVersion(),
                eventSequence,
                AutoParticipantRandomStream.ATTENTION,
                "NEXT"
        );
        double targetHazard = -Math.log(Math.max(MIN_POSITIVE_UNIT, 1.0 - random.nextDouble()));
        double accumulatedHazard = 0.0;
        double sessionCurveMean = normalizedSessionCurveMean(sessionOpen, sessionClose);
        while (cursor.isBefore(sessionClose)) {
            LocalDateTime bucketEnd = nextBucketBoundary(cursor, sessionOpen, sessionClose);
            long seconds = Math.max(1L, Duration.between(cursor, bucketEnd).toSeconds());
            double hazardPerSecond = attentionHazardPerSecond(
                    cursor,
                    sessionOpen,
                    sessionClose,
                    state,
                    intensity,
                    sessionCurveMean
            );
            double bucketHazard = hazardPerSecond * seconds;
            if (accumulatedHazard + bucketHazard >= targetHazard) {
                double secondsIntoBucket = (targetHazard - accumulatedHazard) / hazardPerSecond;
                long roundedSeconds = Math.max(1L, (long) Math.ceil(secondsIntoBucket));
                LocalDateTime result = cursor.plusSeconds(roundedSeconds);
                return result.isBefore(sessionClose) ? result : null;
            }
            accumulatedHazard += bucketHazard;
            cursor = bucketEnd;
        }
        return null;
    }

    public boolean shouldExecuteVoluntaryOrder(
            double signedSignalStrength,
            double independentNoise,
            double fatigue,
            double reentryFactor,
            AutoParticipantActivityState state,
            long behaviorSeed,
            LocalDate tradeDate,
            long eventSequence,
            AutoParticipantV3Policy policy
    ) {
        if (state == null || state == AutoParticipantActivityState.OFFLINE) {
            return false;
        }
        double stateAdjustment = switch (state) {
            case OFFLINE -> -100.0;
            case LOW -> -0.75;
            case NORMAL -> 0.0;
            case HIGH -> 0.65;
        };
        double logit = policy.executionIntercept()
                + stateAdjustment
                + policy.signalSensitivity() * Math.abs(Math.clamp(signedSignalStrength, -1.0, 1.0))
                + Math.clamp(independentNoise, -1.0, 1.0)
                - policy.fatigueSensitivity() * Math.max(0.0, fatigue)
                + Math.log(Math.max(MIN_POSITIVE_UNIT, Math.clamp(reentryFactor, 0.0, 1.0)));
        double probability = 1.0 / (1.0 + Math.exp(-Math.clamp(logit, -40.0, 40.0)));
        double draw = AutoParticipantV3Random.stream(
                behaviorSeed,
                tradeDate,
                policy.policyVersion(),
                eventSequence,
                AutoParticipantRandomStream.FOLLOW_THROUGH,
                "EXECUTE"
        ).nextDouble();
        return draw < probability;
    }

    public double fatigue(
            double priorFatigue,
            long elapsedSeconds,
            long submittedOrderCount,
            double submittedNotionalRatio,
            double executedNotionalRatio,
            boolean recentLargeOrder,
            AutoParticipantV3Policy policy
    ) {
        double decayed = Math.max(0.0, priorFatigue)
                * Math.pow(0.5, Math.max(0L, elapsedSeconds) / policy.fatigueHalfLifeSeconds());
        double countLoad = Math.log1p(Math.max(0L, submittedOrderCount)) * 0.16;
        double notionalLoad = Math.max(0.0, submittedNotionalRatio) * 1.25
                + Math.max(0.0, executedNotionalRatio) * 0.85;
        double largeOrderLoad = recentLargeOrder ? 0.55 : 0.0;
        return Math.max(0.0, decayed + countLoad + notionalLoad + largeOrderLoad);
    }

    public double reentryFactor(long elapsedSeconds, AutoParticipantV3Policy policy) {
        return 1.0 - Math.exp(-Math.max(0L, elapsedSeconds) / policy.reentryTauSeconds());
    }

    public String selectVoluntarySymbol(
            List<SymbolCandidate> candidates,
            long behaviorSeed,
            LocalDate tradeDate,
            long eventSequence,
            AutoParticipantV3Policy policy
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        SplittableRandom random = AutoParticipantV3Random.stream(
                behaviorSeed,
                tradeDate,
                policy.policyVersion(),
                eventSequence,
                AutoParticipantRandomStream.SYMBOL,
                "GUMBEL"
        );
        return candidates.stream()
                .filter(SymbolCandidate::enabled)
                .map(candidate -> new ScoredSymbol(candidate.symbol(), symbolScore(candidate, random.nextDouble())))
                .max(Comparator.comparingDouble(ScoredSymbol::score).thenComparing(ScoredSymbol::symbol))
                .map(ScoredSymbol::symbol)
                .orElse(null);
    }

    private double attentionHazardPerSecond(
            LocalDateTime at,
            LocalDateTime sessionOpen,
            LocalDateTime sessionClose,
            AutoParticipantActivityState state,
            int intensity,
            double sessionCurveMean
    ) {
        double sessionProgress = (double) Duration.between(sessionOpen, at).toSeconds()
                / Math.max(1L, Duration.between(sessionOpen, sessionClose).toSeconds());
        double timeWeight = sessionCurve(sessionProgress) / sessionCurveMean;
        double stateEventsPerSession = switch (state) {
            case OFFLINE -> 0.0;
            case LOW -> 1.5;
            case NORMAL -> 5.0;
            case HIGH -> 12.0;
        };
        double intensityFactor = 0.55 + Math.clamp(intensity, 1, 10) * 0.09;
        long sessionSeconds = Math.max(1L, Duration.between(sessionOpen, sessionClose).toSeconds());
        return Math.max(MIN_POSITIVE_UNIT, stateEventsPerSession * intensityFactor * timeWeight / sessionSeconds);
    }

    private double sessionCurve(double progress) {
        double normalized = Math.clamp(progress, 0.0, 1.0);
        double openPulse = Math.exp(-Math.pow((normalized - 0.08) / 0.13, 2));
        double closePulse = Math.exp(-Math.pow((normalized - 0.88) / 0.16, 2));
        double lunchDip = Math.exp(-Math.pow((normalized - 0.50) / 0.11, 2));
        return Math.max(0.18, 0.72 + 0.85 * openPulse + 0.65 * closePulse - 0.45 * lunchDip);
    }

    private double normalizedSessionCurveMean(
            LocalDateTime sessionOpen,
            LocalDateTime sessionClose
    ) {
        long sessionSeconds = Math.max(1L, Duration.between(sessionOpen, sessionClose).toSeconds());
        int bucketCount = Math.max(
                1,
                (int) Math.ceil((double) sessionSeconds / SESSION_BUCKET_SECONDS)
        );
        double total = 0.0;
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            double midpoint = Math.min(1.0, (bucket + 0.5) / bucketCount);
            total += sessionCurve(midpoint);
        }
        return Math.max(MIN_POSITIVE_UNIT, total / bucketCount);
    }

    private LocalDateTime nextBucketBoundary(
            LocalDateTime cursor,
            LocalDateTime sessionOpen,
            LocalDateTime sessionClose
    ) {
        long elapsedSeconds = Math.max(0L, Duration.between(sessionOpen, cursor).toSeconds());
        long nextBucketSeconds = (Math.floorDiv(elapsedSeconds, SESSION_BUCKET_SECONDS) + 1L)
                * SESSION_BUCKET_SECONDS;
        LocalDateTime boundary = sessionOpen.plusSeconds(nextBucketSeconds);
        return boundary.isBefore(sessionClose) ? boundary : sessionClose;
    }

    private double symbolScore(SymbolCandidate candidate, double unitDraw) {
        double safeDraw = Math.clamp(unitDraw, MIN_POSITIVE_UNIT, 1.0 - MIN_POSITIVE_UNIT);
        double gumbel = -Math.log(-Math.log(safeDraw));
        return candidate.stablePreference()
                + candidate.signalImportance()
                + candidate.symbolInterest()
                + candidate.eventNoiseScale() * gumbel;
    }

    private AutoParticipantActivityState sampleCategorical(double[] probabilities, double draw) {
        double cumulative = 0.0;
        for (int index = 0; index < probabilities.length; index++) {
            cumulative += probabilities[index];
            if (draw < cumulative) {
                return AutoParticipantActivityState.values()[index];
            }
        }
        return AutoParticipantActivityState.HIGH;
    }

    private double[] normalize(double[] weights) {
        double sum = 0.0;
        for (double weight : weights) {
            sum += Math.max(0.0, weight);
        }
        if (sum <= 0.0) {
            throw new IllegalArgumentException("At least one positive weight is required");
        }
        double[] normalized = new double[weights.length];
        for (int index = 0; index < weights.length; index++) {
            normalized[index] = Math.max(0.0, weights[index]) / sum;
        }
        return normalized;
    }

    public record SymbolCandidate(
            String symbol,
            boolean enabled,
            double stablePreference,
            double signalImportance,
            double symbolInterest,
            double eventNoiseScale
    ) {
        public SymbolCandidate {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("Symbol is required");
            }
            if (!Double.isFinite(stablePreference)
                    || !Double.isFinite(signalImportance)
                    || !Double.isFinite(symbolInterest)
                    || !Double.isFinite(eventNoiseScale)
                    || eventNoiseScale < 0.0) {
                throw new IllegalArgumentException("Symbol selection weights must be finite and noise non-negative");
            }
        }
    }

    private record ScoredSymbol(String symbol, double score) {
    }
}
