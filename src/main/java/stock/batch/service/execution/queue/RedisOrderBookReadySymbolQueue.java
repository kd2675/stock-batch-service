package stock.batch.service.execution.queue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
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

    private static final RedisScript<Long> RECONCILE_SCRIPT = RedisScript.of(
            """
            local repaired = 0
            local verifyMembership =
                redis.call('scard', KEYS[1]) ~= redis.call('llen', KEYS[2])
            local queueMembership = nil
            if verifyMembership then
                queueMembership = {}
                local queueSnapshot =
                    redis.call('lrange', KEYS[2], 0, tonumber(ARGV[1]) - 1)
                for _, queuedSymbol in ipairs(queueSnapshot) do
                    queueMembership[queuedSymbol] = true
                end
            end
            for index = 2, #ARGV do
                local symbol = ARGV[index]
                if redis.call('sismember', KEYS[1], symbol) == 0 then
                    redis.call('sadd', KEYS[1], symbol)
                    if not verifyMembership then
                        redis.call('rpush', KEYS[2], symbol)
                        repaired = repaired + 1
                    end
                end
                if verifyMembership and not queueMembership[symbol] then
                    redis.call('rpush', KEYS[2], symbol)
                    queueMembership[symbol] = true
                    repaired = repaired + 1
                end
            end
            return repaired
            """,
            Long.class
    );

    private static final RedisScript<Long> ACQUIRE_LEASE_SCRIPT = RedisScript.of(
            """
            if redis.call('exists', KEYS[2]) == 1 then
                return 0
            end
            if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
                redis.call('set', KEYS[2], ARGV[1], 'PX', ARGV[3])
                return 1
            end
            return -1
            """,
            Long.class
    );

    private static final RedisScript<Long> RELEASE_LEASE_SCRIPT = RedisScript.of(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class
    );

    private static final RedisScript<Long> UPDATE_CURSOR_SCRIPT = RedisScript.of(
            """
            if redis.call('get', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            if ARGV[2] == '' then
                redis.call('del', KEYS[2])
            else
                redis.call('set', KEYS[2], ARGV[2])
            end
            redis.call('set', KEYS[3], ARGV[1], 'PX', ARGV[3])
            return 1
            """,
            Long.class
    );

    private static final long MIN_RECONCILIATION_LEASE_TTL_MILLIS = 500L;
    private static final long MAX_RECONCILIATION_LEASE_TTL_MILLIS = 60_000L;
    private static final int MAX_RECONCILIATION_LIST_SCAN_LENGTH = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final String setKey;
    private final String queueKey;
    private final String reconciliationLeaseKey;
    private final String reconciliationCursorKey;
    private final String reconciliationCooldownKey;
    private final Duration reconciliationLeaseTtl;
    private final String leaseOwnerPrefix = UUID.randomUUID().toString();
    private final MeterRegistry meterRegistry;

    public RedisOrderBookReadySymbolQueue(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${stock.batch.execution.ready-symbol-queue.set-key:stock:orderbook:execution:ready-symbol-set}") String setKey,
            @Value("${stock.batch.execution.ready-symbol-queue.queue-key:stock:orderbook:execution:ready-symbol-queue}") String queueKey,
            @Value("${stock.batch.execution.ready-symbol-queue.reconciliation-lease-key:stock:orderbook:execution:ready-symbol-reconciliation-lease}") String reconciliationLeaseKey,
            @Value("${stock.batch.execution.ready-symbol-queue.reconciliation-lease-ttl-ms:3000}") long reconciliationLeaseTtlMillis
    ) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.setKey = requireKey(setKey, "set-key");
        this.queueKey = requireKey(queueKey, "queue-key");
        this.reconciliationLeaseKey = requireKey(reconciliationLeaseKey, "reconciliation-lease-key");
        this.reconciliationCursorKey = this.reconciliationLeaseKey + ":cursor";
        this.reconciliationCooldownKey = this.reconciliationLeaseKey + ":cooldown";
        if (reconciliationLeaseTtlMillis < MIN_RECONCILIATION_LEASE_TTL_MILLIS
                || reconciliationLeaseTtlMillis > MAX_RECONCILIATION_LEASE_TTL_MILLIS) {
            throw new IllegalArgumentException(
                    "stock.batch.execution.ready-symbol-queue.reconciliation-lease-ttl-ms "
                            + "must be between %d and %d"
                            .formatted(
                                    MIN_RECONCILIATION_LEASE_TTL_MILLIS,
                                    MAX_RECONCILIATION_LEASE_TTL_MILLIS
                            )
            );
        }
        this.reconciliationLeaseTtl = Duration.ofMillis(reconciliationLeaseTtlMillis);
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
            recordRedisFailure("enqueue");
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
            recordRedisFailure("poll");
            log.warn("Redis order book ready symbol poll failed: reason={}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public int reconcileAll(Collection<String> symbols) {
        List<String> normalizedSymbols = symbols.stream()
                .map(this::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank())
                .distinct()
                .toList();
        if (normalizedSymbols.isEmpty()) {
            return 0;
        }
        List<String> arguments = new ArrayList<>(normalizedSymbols.size() + 1);
        arguments.add(Integer.toString(MAX_RECONCILIATION_LIST_SCAN_LENGTH));
        arguments.addAll(normalizedSymbols);
        try {
            Long repairedCount = redisTemplate.execute(
                    RECONCILE_SCRIPT,
                    List.of(setKey, queueKey),
                    arguments.toArray()
            );
            return repairedCount == null ? 0 : Math.toIntExact(repairedCount);
        } catch (RuntimeException ex) {
            recordRedisFailure("reconcile");
            throw ex;
        }
    }

    @Override
    public Optional<ReconciliationLease> tryAcquireReconciliationLease(long minimumIntervalMillis) {
        if (minimumIntervalMillis <= 0L) {
            throw new IllegalArgumentException("minimumIntervalMillis must be positive");
        }
        String token = leaseOwnerPrefix + ":" + UUID.randomUUID();
        boolean acquired = false;
        try {
            Long acquireResult = redisTemplate.execute(
                    ACQUIRE_LEASE_SCRIPT,
                    List.of(reconciliationLeaseKey, reconciliationCooldownKey),
                    token,
                    Long.toString(reconciliationLeaseTtl.toMillis()),
                    Long.toString(minimumIntervalMillis)
            );
            acquired = acquireResult != null && acquireResult == 1L;
            if (!acquired) {
                return Optional.empty();
            }
            String cursor = normalizeSymbol(redisTemplate.opsForValue().get(reconciliationCursorKey));
            return Optional.of(new RedisReconciliationLease(token, cursor, minimumIntervalMillis));
        } catch (RuntimeException ex) {
            recordRedisFailure("lease-acquire");
            log.warn("Redis order book ready symbol reconciliation lease acquisition failed: reason={}", ex.getMessage());
            if (acquired) {
                releaseReconciliationLease(token);
            }
            return Optional.empty();
        }
    }

    private boolean updateReconciliationCursor(
            String token,
            String nextCursor,
            long minimumIntervalMillis
    ) {
        try {
            Long updated = redisTemplate.execute(
                    UPDATE_CURSOR_SCRIPT,
                    List.of(
                            reconciliationLeaseKey,
                            reconciliationCursorKey,
                            reconciliationCooldownKey
                    ),
                    token,
                    normalizeSymbol(nextCursor),
                    Long.toString(minimumIntervalMillis)
            );
            return updated != null && updated == 1L;
        } catch (RuntimeException ex) {
            recordRedisFailure("cursor-update");
            log.warn("Redis order book ready symbol reconciliation cursor update failed: reason={}", ex.getMessage());
            return false;
        }
    }

    private void releaseReconciliationLease(String token) {
        try {
            redisTemplate.execute(
                    RELEASE_LEASE_SCRIPT,
                    List.of(reconciliationLeaseKey),
                    token
            );
        } catch (RuntimeException ex) {
            recordRedisFailure("lease-release");
            log.warn("Redis order book ready symbol reconciliation lease release failed: reason={}", ex.getMessage());
        }
    }

    private void recordRedisFailure(String operation) {
        meterRegistry.counter(
                "stock.orderbook.execution.ready.queue.redis.failures",
                "operation",
                operation
        ).increment();
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

    private final class RedisReconciliationLease implements ReconciliationLease {

        private final String token;
        private final String cursor;
        private final long minimumIntervalMillis;

        private RedisReconciliationLease(String token, String cursor, long minimumIntervalMillis) {
            this.token = token;
            this.cursor = cursor;
            this.minimumIntervalMillis = minimumIntervalMillis;
        }

        @Override
        public String cursor() {
            return cursor;
        }

        @Override
        public boolean updateCursor(String nextCursor) {
            return updateReconciliationCursor(token, nextCursor, minimumIntervalMillis);
        }

        @Override
        public void close() {
            releaseReconciliationLease(token);
        }
    }
}
