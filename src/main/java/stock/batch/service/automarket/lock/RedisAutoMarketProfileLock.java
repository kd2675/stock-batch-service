package stock.batch.service.automarket.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Slf4j
@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-lock.type", havingValue = "redis", matchIfMissing = true)
public class RedisAutoMarketProfileLock implements AutoMarketProfileLock {

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long ttlSeconds;
    private final String ownerPrefix;

    public RedisAutoMarketProfileLock(
            StringRedisTemplate redisTemplate,
            @Value("${stock.batch.auto-market.profile-lock.key-prefix:stock:automarket:profile:lock:}") String keyPrefix,
            @Value("${stock.batch.auto-market.profile-lock.ttl-seconds:120}") long ttlSeconds
    ) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("stock.batch.auto-market.profile-lock.ttl-seconds must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix == null ? "stock:automarket:profile:lock:" : keyPrefix;
        this.ttlSeconds = ttlSeconds;
        this.ownerPrefix = "stock-batch-auto-market-" + UUID.randomUUID();
    }

    @Override
    public Optional<LockHandle> tryLock(AutoParticipantProfileType profileType) {
        if (profileType == null) {
            return Optional.empty();
        }
        String key = keyPrefix + profileType.name();
        String token = ownerPrefix + ":" + UUID.randomUUID();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds));
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new RedisLockHandle(key, token));
    }

    private final class RedisLockHandle implements LockHandle {

        private final String key;
        private final String token;

        private RedisLockHandle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            try {
                redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
            } catch (RuntimeException ex) {
                log.warn("Redis auto-market profile lock release failed: key={}, reason={}", key, ex.getMessage());
            }
        }
    }
}
