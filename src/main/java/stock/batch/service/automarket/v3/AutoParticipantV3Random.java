package stock.batch.service.automarket.v3;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.SplittableRandom;

final class AutoParticipantV3Random {

    private AutoParticipantV3Random() {
    }

    static SplittableRandom stream(
            long behaviorSeed,
            LocalDate tradeDate,
            long policyVersion,
            long eventSequence,
            AutoParticipantRandomStream stream,
            String salt
    ) {
        if (tradeDate == null || policyVersion <= 0 || eventSequence < 0 || stream == null) {
            throw new IllegalArgumentException("V3 random stream requires date, positive policy, and non-negative event sequence");
        }
        long hash = behaviorSeed ^ 0x9e3779b97f4a7c15L;
        hash = mix(hash, tradeDate.toString());
        hash = mix(hash, Long.toString(policyVersion));
        hash = mix(hash, Long.toString(eventSequence));
        hash = mix(hash, stream.name());
        hash = mix(hash, salt == null ? "" : salt);
        return new SplittableRandom(hash);
    }

    private static long mix(long seed, String value) {
        long hash = seed;
        for (byte next : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= next & 0xffL;
            hash *= 0x100000001b3L;
            hash ^= hash >>> 32;
        }
        return hash;
    }
}
