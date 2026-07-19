package stock.batch.service.automarket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

class RedisAutoMarketReadyProfileQueueTest {

    @Test
    @SuppressWarnings("unchecked")
    void hasDueProfile_dueMember_returnsTrueWithOneReadOnlyScriptCall() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("queue")),
                any(String.class)
        )).thenReturn(1L);
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");

        boolean due = queue.hasDueProfile(LocalDateTime.of(2026, 7, 3, 9, 0));

        assertThat(due).isTrue();
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("queue")),
                any(String.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasDueProfile_redisFailure_failsClosedWithoutDatabaseFallback() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("queue")),
                any(String.class)
        )).thenThrow(new IllegalStateException("redis unavailable"));
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");

        boolean due = queue.hasDueProfile(LocalDateTime.of(2026, 7, 3, 9, 0));

        assertThat(due).isFalse();
    }

    @Test
    void enqueue_existingProfileScoreUpdate_returnsSuccess() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.add(eq("queue"), eq("MARKET_MAKER"), anyDouble())).thenReturn(false);
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");

        boolean result = queue.enqueue(AutoParticipantProfileType.MARKET_MAKER, LocalDateTime.of(2026, 7, 3, 9, 0));

        assertThat(result).isTrue();
    }

    @Test
    void removeAll_profiles_removesThemInSingleRedisCall() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.remove(eq("queue"), eq("MARKET_MAKER"), eq("SCALPER"))).thenReturn(2L);
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");

        int removedCount = queue.removeAll(List.of(
                AutoParticipantProfileType.MARKET_MAKER,
                AutoParticipantProfileType.SCALPER
        ));

        assertThat(removedCount).isEqualTo(2);
        verify(zSetOperations).remove("queue", "MARKET_MAKER", "SCALPER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaceAll_profiles_replacesQueueWithOneAtomicScriptCall() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("queue")),
                any(String.class),
                eq("MARKET_MAKER"),
                any(String.class),
                eq("SCALPER")
        )).thenReturn(2L);
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 5, 30);

        int storedCount = queue.replaceAll(List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.MARKET_MAKER, now),
                new AutoMarketReadyProfileQueue.ReadyProfile(AutoParticipantProfileType.SCALPER, now.plusSeconds(3))
        ));

        assertThat(storedCount).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaceAll_emptyProfiles_clearsQueueWithAtomicScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("queue"))
        )).thenReturn(0L);
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");

        int storedCount = queue.replaceAll(List.of());

        assertThat(storedCount).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaceAll_redisFailure_propagatesPreOpenFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("queue")),
                any(String.class),
                eq("MARKET_MAKER")
        )).thenThrow(new IllegalStateException("redis unavailable"));
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(redisTemplate, "queue", "Asia/Seoul");

        assertThatThrownBy(() -> queue.replaceAll(List.of(
                new AutoMarketReadyProfileQueue.ReadyProfile(
                        AutoParticipantProfileType.MARKET_MAKER,
                        LocalDateTime.of(2026, 7, 3, 5, 30)
                )
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue replacement failed");
    }

    @Test
    void snapshot_profiles_readsCompleteBoundedSortedSet() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        LocalDateTime readyAt = LocalDateTime.of(2026, 7, 3, 5, 30, 3);
        double score = readyAt.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.rangeWithScores("queue", 0, -1)).thenReturn(Set.of(tuple));
        when(tuple.getValue()).thenReturn("MARKET_MAKER");
        when(tuple.getScore()).thenReturn(score);
        RedisAutoMarketReadyProfileQueue queue = new RedisAutoMarketReadyProfileQueue(
                redisTemplate,
                "queue",
                "Asia/Seoul"
        );

        assertThat(queue.snapshot())
                .containsEntry(AutoParticipantProfileType.MARKET_MAKER, readyAt)
                .hasSize(1);
    }
}
