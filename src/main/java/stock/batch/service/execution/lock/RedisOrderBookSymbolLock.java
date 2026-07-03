package stock.batch.service.execution.lock;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "stock.batch.execution.symbol-lock.type", havingValue = "redis", matchIfMissing = true)
public class RedisOrderBookSymbolLock implements OrderBookSymbolLock {

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long ttlSeconds;
    private final String ownerPrefix;

    public RedisOrderBookSymbolLock(
            StringRedisTemplate redisTemplate,
            @Value("${stock.batch.execution.symbol-lock.key-prefix:stock:orderbook:lock:}") String keyPrefix,
            @Value("${stock.batch.execution.symbol-lock.ttl-seconds:120}") long ttlSeconds
    ) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("stock.batch.execution.symbol-lock.ttl-seconds must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix == null ? "stock:orderbook:lock:" : keyPrefix;
        this.ttlSeconds = ttlSeconds;
        this.ownerPrefix = "stock-batch-order-book-" + UUID.randomUUID();
    }

    @Override
    public Optional<LockHandle> tryLock(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            return Optional.empty();
        }
        String key = keyPrefix + normalizedSymbol;
        String token = ownerPrefix + ":" + UUID.randomUUID();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds));
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new RedisLockHandle(key, token));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
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
                redisTemplate.execute(RELEASE_SCRIPT, java.util.List.of(key), token);
            } catch (RuntimeException ex) {
                log.warn("Redis order book symbol lock release failed: key={}, reason={}", key, ex.getMessage());
            }
        }
    }
}
