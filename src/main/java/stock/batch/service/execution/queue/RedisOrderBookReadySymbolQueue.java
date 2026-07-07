package stock.batch.service.execution.queue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "stock.batch.execution.ready-symbol-queue.type", havingValue = "redis", matchIfMissing = true)
public class RedisOrderBookReadySymbolQueue implements OrderBookReadySymbolQueue {

    private static final RedisScript<Long> ENQUEUE_SCRIPT = RedisScript.of(
            """
            if redis.call('sadd', KEYS[1], ARGV[1]) == 1 then
                return redis.call('rpush', KEYS[2], ARGV[1])
            end
            return 0
            """,
            Long.class
    );

    private static final RedisScript<String> POLL_SCRIPT = RedisScript.of(
            """
            local symbol = redis.call('lpop', KEYS[2])
            if symbol then
                redis.call('srem', KEYS[1], symbol)
            end
            return symbol
            """,
            String.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String setKey;
    private final String queueKey;

    public RedisOrderBookReadySymbolQueue(
            StringRedisTemplate redisTemplate,
            @Value("${stock.batch.execution.ready-symbol-queue.set-key:stock:orderbook:execution:ready-symbol-set}") String setKey,
            @Value("${stock.batch.execution.ready-symbol-queue.queue-key:stock:orderbook:execution:ready-symbol-queue}") String queueKey
    ) {
        this.redisTemplate = redisTemplate;
        this.setKey = requireKey(setKey, "set-key");
        this.queueKey = requireKey(queueKey, "queue-key");
    }

    @Override
    public boolean enqueue(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            return false;
        }
        try {
            Long pushedLength = redisTemplate.execute(ENQUEUE_SCRIPT, List.of(setKey, queueKey), normalizedSymbol);
            return pushedLength != null && pushedLength > 0;
        } catch (RuntimeException ex) {
            log.warn("Redis order book ready symbol enqueue failed: symbol={}, reason={}", normalizedSymbol, ex.getMessage());
            return false;
        }
    }

    @Override
    public Optional<String> poll() {
        try {
            String symbol = redisTemplate.execute(POLL_SCRIPT, List.of(setKey, queueKey));
            return Optional.ofNullable(normalizeSymbol(symbol)).filter(value -> !value.isBlank());
        } catch (RuntimeException ex) {
            log.warn("Redis order book ready symbol poll failed: reason={}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String requireKey(String key, String propertyName) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("stock.batch.execution.ready-symbol-queue.%s must not be blank".formatted(propertyName));
        }
        return key;
    }
}
