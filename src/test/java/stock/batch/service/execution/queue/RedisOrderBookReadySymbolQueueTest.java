package stock.batch.service.execution.queue;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOrderBookReadySymbolQueueTest {

    @Test
    void tryAcquireReconciliationLease_acquired_releasesOnlyWithOwnedToken() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of(
                        "ready-reconciliation-lease",
                        "ready-reconciliation-lease:cooldown"
                )),
                any(String.class),
                eq("3000"),
                eq("1000")
        )).thenReturn(1L);
        when(valueOperations.get("ready-reconciliation-lease:cursor")).thenReturn("DEMO008");
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of(
                        "ready-reconciliation-lease",
                        "ready-reconciliation-lease:cursor",
                        "ready-reconciliation-lease:cooldown"
                )),
                any(Object[].class)
        )).thenReturn(1L);
        RedisOrderBookReadySymbolQueue queue = new RedisOrderBookReadySymbolQueue(
                redisTemplate,
                new SimpleMeterRegistry(),
                "ready-set",
                "ready-queue",
                "ready-reconciliation-lease",
                3_000L
        );
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<RedisScript<Long>> acquireScriptCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(RedisScript.class);

        OrderBookReadySymbolQueue.ReconciliationLease lease =
                queue.tryAcquireReconciliationLease(1_000L).orElseThrow();
        assertThat(lease.cursor()).isEqualTo("DEMO008");
        verify(redisTemplate).execute(
                acquireScriptCaptor.capture(),
                eq(List.of(
                        "ready-reconciliation-lease",
                        "ready-reconciliation-lease:cooldown"
                )),
                tokenCaptor.capture(),
                eq("3000"),
                eq("1000")
        );
        assertThat(acquireScriptCaptor.getValue().getScriptAsString())
                .contains(
                        "redis.call('exists', KEYS[2])",
                        "redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])",
                        "redis.call('set', KEYS[2], ARGV[1], 'PX', ARGV[3])"
                );
        assertThat(lease.updateCursor("DEMO016")).isTrue();
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "ready-reconciliation-lease",
                        "ready-reconciliation-lease:cursor",
                        "ready-reconciliation-lease:cooldown"
                )),
                eq(tokenCaptor.getValue()),
                eq("DEMO016"),
                eq("1000")
        );
        lease.close();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("ready-reconciliation-lease")),
                eq(tokenCaptor.getValue())
        );
    }

    @Test
    void tryAcquireReconciliationLease_heldByOtherInstance_returnsEmpty() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of(
                        "ready-reconciliation-lease",
                        "ready-reconciliation-lease:cooldown"
                )),
                any(String.class),
                eq("3000"),
                eq("1000")
        )).thenReturn(0L);
        RedisOrderBookReadySymbolQueue queue = new RedisOrderBookReadySymbolQueue(
                redisTemplate,
                new SimpleMeterRegistry(),
                "ready-set",
                "ready-queue",
                "ready-reconciliation-lease",
                3_000L
        );

        assertThat(queue.tryAcquireReconciliationLease(1_000L)).isEmpty();
        verify(valueOperations, never()).get("ready-reconciliation-lease:cursor");
    }

    @Test
    void tryAcquireReconciliationLease_redisFailure_recordsLowCardinalityMetric() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of(
                        "ready-reconciliation-lease",
                        "ready-reconciliation-lease:cooldown"
                )),
                any(String.class),
                eq("3000"),
                eq("1000")
        )).thenThrow(new IllegalStateException("redis unavailable"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisOrderBookReadySymbolQueue queue = new RedisOrderBookReadySymbolQueue(
                redisTemplate,
                meterRegistry,
                "ready-set",
                "ready-queue",
                "ready-reconciliation-lease",
                3_000L
        );

        queue.tryAcquireReconciliationLease(1_000L);

        assertThat(meterRegistry.counter(
                "stock.orderbook.execution.ready.queue.redis.failures",
                "operation",
                "lease-acquire"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void reconcileAll_setOnlyDrift_repairsMissingQueueEntryWithBoundedScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("ready-set", "ready-queue")),
                eq("10000"),
                eq("DEMO001"),
                eq("DEMO002")
        )).thenReturn(2L);
        RedisOrderBookReadySymbolQueue queue = new RedisOrderBookReadySymbolQueue(
                redisTemplate,
                new SimpleMeterRegistry(),
                "ready-set",
                "ready-queue",
                "ready-reconciliation-lease",
                3_000L
        );
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(RedisScript.class);

        int repairedCount = queue.reconcileAll(List.of(" demo001 ", "DEMO002", "demo001"));

        assertThat(repairedCount).isEqualTo(2);
        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of("ready-set", "ready-queue")),
                eq("10000"),
                eq("DEMO001"),
                eq("DEMO002")
        );
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains(
                        "redis.call('scard', KEYS[1]) ~= redis.call('llen', KEYS[2])",
                        "redis.call('lrange', KEYS[2], 0, tonumber(ARGV[1]) - 1)",
                        "if verifyMembership and not queueMembership[symbol] then",
                        "rpush"
                )
                .doesNotContain("lpos");
    }

    @Test
    void constructor_reconciliationLeaseTtlOutsideBound_rejectsStartup() {
        assertThatThrownBy(() -> new RedisOrderBookReadySymbolQueue(
                mock(StringRedisTemplate.class),
                new SimpleMeterRegistry(),
                "ready-set",
                "ready-queue",
                "ready-reconciliation-lease",
                100L
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation-lease-ttl-ms must be between 500 and 60000");
    }
}
