package stock.batch.service.automarket.queue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Slf4j
@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-queue.type", havingValue = "redis", matchIfMissing = true)
public class RedisAutoMarketReadyProfileQueue implements AutoMarketReadyProfileQueue {

    private static final RedisScript<Long> HAS_DUE_SCRIPT = RedisScript.of(
            """
            local profiles = redis.call('zrangebyscore', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 1)
            if #profiles == 0 then
                return 0
            end
            return 1
            """,
            Long.class
    );

    private static final RedisScript<String> CLAIM_SCRIPT = RedisScript.of(
            """
            local profiles = redis.call('zrangebyscore', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 1)
            if #profiles == 0 then
                return nil
            end
            local profile = profiles[1]
            if redis.call('zrem', KEYS[1], profile) == 1 then
                return profile
            end
            return nil
            """,
            String.class
    );

    private static final RedisScript<Long> REPLACE_ALL_SCRIPT = RedisScript.of(
            """
            redis.call('del', KEYS[1])
            for index = 1, #ARGV, 2 do
                redis.call('zadd', KEYS[1], ARGV[index], ARGV[index + 1])
            end
            return redis.call('zcard', KEYS[1])
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String queueKey;
    private final ZoneId zoneId;

    public RedisAutoMarketReadyProfileQueue(
            StringRedisTemplate redisTemplate,
            @Value("${stock.batch.auto-market.profile-queue.key:stock:auto-market:generation:ready-profile-zset}") String queueKey,
            @Value("${stock.batch.auto-market.profile-queue.zone-id:Asia/Seoul}") String zoneId
    ) {
        this.redisTemplate = redisTemplate;
        this.queueKey = requireKey(queueKey);
        this.zoneId = ZoneId.of(zoneId == null || zoneId.isBlank() ? "Asia/Seoul" : zoneId);
    }

    @Override
    public boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
        if (profileType == null || readyAt == null) {
            return false;
        }
        try {
            redisTemplate.opsForZSet().add(queueKey, profileType.name(), epochMillis(readyAt));
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                    "Redis auto market ready profile enqueue failed: profileType={}, reason={}",
                    profileType,
                    ex.getMessage()
            );
            return false;
        }
    }

    @Override
    public boolean hasDueProfile(LocalDateTime now) {
        if (now == null) {
            return false;
        }
        try {
            Long result = redisTemplate.execute(
                    HAS_DUE_SCRIPT,
                    List.of(queueKey),
                    String.valueOf(epochMillis(now))
            );
            return result != null && result > 0;
        } catch (RuntimeException ex) {
            log.warn("Redis auto market ready profile check failed: reason={}", ex.getMessage());
            return false;
        }
    }

    @Override
    public Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now) {
        if (now == null) {
            return Optional.empty();
        }
        try {
            String rawProfile = redisTemplate.execute(CLAIM_SCRIPT, List.of(queueKey), String.valueOf(epochMillis(now)));
            return Optional.ofNullable(rawProfile)
                    .map(AutoParticipantProfileType::parseOrDefault);
        } catch (RuntimeException ex) {
            log.warn("Redis auto market ready profile claim failed: reason={}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public int removeAll(Collection<AutoParticipantProfileType> profileTypes) {
        if (profileTypes == null || profileTypes.isEmpty()) {
            return 0;
        }
        Object[] profileNames = profileTypes.stream()
                .filter(profileType -> profileType != null)
                .map(AutoParticipantProfileType::name)
                .distinct()
                .toArray();
        if (profileNames.length == 0) {
            return 0;
        }
        try {
            Long removedCount = redisTemplate.opsForZSet().remove(queueKey, profileNames);
            return removedCount == null ? 0 : Math.toIntExact(removedCount);
        } catch (RuntimeException ex) {
            log.warn("Redis auto market ready profile removal failed: profiles={}, reason={}", profileTypes, ex.getMessage());
            return 0;
        }
    }

    @Override
    public int replaceAll(Collection<ReadyProfile> profiles) {
        Map<AutoParticipantProfileType, LocalDateTime> readyAtByProfile = validatedProfiles(profiles);
        List<String> arguments = new ArrayList<>(readyAtByProfile.size() * 2);
        readyAtByProfile.forEach((profileType, readyAt) -> {
            arguments.add(String.valueOf(epochMillis(readyAt)));
            arguments.add(profileType.name());
        });
        try {
            Long storedCount = redisTemplate.execute(
                    REPLACE_ALL_SCRIPT,
                    List.of(queueKey),
                    arguments.toArray()
            );
            if (storedCount == null) {
                throw new IllegalStateException("Redis returned no profile queue replacement result");
            }
            return Math.toIntExact(storedCount);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Redis auto market ready profile queue replacement failed: queue=" + queueKey,
                    ex
            );
        }
    }

    @Override
    public Map<AutoParticipantProfileType, LocalDateTime> snapshot() {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                    .rangeWithScores(queueKey, 0, -1);
            if (tuples == null || tuples.isEmpty()) {
                return Map.of();
            }
            Map<AutoParticipantProfileType, LocalDateTime> profiles = new LinkedHashMap<>();
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String value = tuple.getValue();
                Double score = tuple.getScore();
                if (value == null || score == null) {
                    throw new IllegalStateException("Redis profile queue contains an incomplete tuple");
                }
                AutoParticipantProfileType profileType = AutoParticipantProfileType.valueOf(value);
                LocalDateTime readyAt = Instant.ofEpochMilli(score.longValue())
                        .atZone(zoneId)
                        .toLocalDateTime();
                profiles.put(profileType, readyAt);
            }
            return Map.copyOf(profiles);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Redis auto market ready profile queue snapshot failed: queue=" + queueKey,
                    ex
            );
        }
    }

    private double epochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(zoneId).toInstant().toEpochMilli();
    }

    private Map<AutoParticipantProfileType, LocalDateTime> validatedProfiles(Collection<ReadyProfile> profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException("Ready profiles must not be null");
        }
        Map<AutoParticipantProfileType, LocalDateTime> readyAtByProfile = new LinkedHashMap<>();
        for (ReadyProfile profile : profiles) {
            if (profile == null || profile.profileType() == null || profile.readyAt() == null) {
                throw new IllegalArgumentException("Ready profile type and ready time must not be null");
            }
            readyAtByProfile.put(profile.profileType(), profile.readyAt());
        }
        return readyAtByProfile;
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("stock.batch.auto-market.profile-queue.key must not be blank");
        }
        return key;
    }
}
