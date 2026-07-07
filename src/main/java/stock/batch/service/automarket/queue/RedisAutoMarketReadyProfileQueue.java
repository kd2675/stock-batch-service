package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Slf4j
@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-queue.type", havingValue = "redis", matchIfMissing = true)
public class RedisAutoMarketReadyProfileQueue implements AutoMarketReadyProfileQueue {

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

    private double epochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(zoneId).toInstant().toEpochMilli();
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("stock.batch.auto-market.profile-queue.key must not be blank");
        }
        return key;
    }
}
