package stock.batch.service.automarket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

class RedisAutoMarketReadyProfileQueueTest {

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
}
