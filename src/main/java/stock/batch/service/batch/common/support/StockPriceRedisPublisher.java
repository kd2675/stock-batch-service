package stock.batch.service.batch.common.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockPriceRedisPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${stock.batch.market-data.price-cache-ttl-seconds:60}")
    private long priceCacheTtlSeconds;

    public void publish(String symbol, BigDecimal currentPrice, LocalDateTime priceTime, String provider) {
        try {
            redisTemplate.opsForValue().set("stock:price:" + symbol, currentPrice.toPlainString(), priceCacheTtl());
            redisTemplate.convertAndSend(
                    "stock.price." + symbol,
                    objectMapper.writeValueAsString(new PriceEvent(symbol, currentPrice, priceTime.toString(), provider))
            );
        } catch (JsonProcessingException ex) {
            log.debug("Redis price event serialization skipped: symbol={}, reason={}", symbol, ex.getMessage());
        } catch (RuntimeException ex) {
            log.debug("Redis price publish skipped: symbol={}, reason={}", symbol, ex.getMessage());
        }
    }

    private Duration priceCacheTtl() {
        return Duration.ofSeconds(Math.max(1, priceCacheTtlSeconds));
    }

    private record PriceEvent(
            String symbol,
            BigDecimal currentPrice,
            String priceTime,
            String provider
    ) {
    }
}
