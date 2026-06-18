package stock.batch.service.marketdata.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.marketdata.provider.MarketPriceProvider;
import stock.batch.service.marketdata.provider.MarketPriceQuote;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataRefreshService {
    private static final BigDecimal DEFAULT_REFERENCE_PRICE = BigDecimal.ONE;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final MarketPriceProvider marketPriceProvider;
    private final ObjectMapper objectMapper;

    @Value("${stock.batch.market-data.price-cache-ttl-seconds:60}")
    private long priceCacheTtlSeconds;

    @Transactional
    public int refreshWatchedPrices() {
        List<PriceRow> prices = jdbcTemplate.query(
                """
                select watched.symbol, p.current_price, watched.reference_price
                from (
                    select symbol, max(reference_price) as reference_price
                    from (
                        select symbol, cast(null as decimal(19, 2)) as reference_price
                        from stock_instrument
                        where enabled = true
                        union all
                        select symbol, limit_price as reference_price
                        from stock_order
                        where status in ('PENDING', 'PARTIALLY_FILLED')
                          and limit_price is not null
                        union all
                        select symbol, average_price as reference_price
                        from stock_holding
                        where quantity > 0
                    ) watched_raw
                    group by symbol
                ) watched
                left join stock_price p on p.symbol = watched.symbol
                order by watched.symbol asc
                """,
                (rs, rowNum) -> new PriceRow(
                        rs.getString("symbol"),
                        rs.getBigDecimal("current_price"),
                        rs.getBigDecimal("reference_price")
                )
        );

        int refreshedCount = 0;
        for (PriceRow price : prices) {
            try {
                BigDecimal referencePrice = resolveReferencePrice(price);
                MarketPriceQuote quote = normalizeQuote(price.symbol(), marketPriceProvider.fetch(price.symbol(), referencePrice));
                upsertPrice(quote, price.currentPrice());
                insertPriceTick(quote);
                publishPrice(quote);
                refreshedCount++;
            } catch (RuntimeException ex) {
                log.warn("Market price refresh skipped: symbol={}, reason={}", price.symbol(), ex.getMessage());
            }
        }
        return refreshedCount;
    }

    private BigDecimal resolveReferencePrice(PriceRow price) {
        if (price.currentPrice() != null) {
            return price.currentPrice();
        }
        if (price.referencePrice() != null && price.referencePrice().compareTo(BigDecimal.ZERO) > 0) {
            return price.referencePrice();
        }
        return DEFAULT_REFERENCE_PRICE;
    }

    private void upsertPrice(MarketPriceQuote quote, BigDecimal previousPrice) {
        int updatedRows = jdbcTemplate.update(
                "update stock_price set current_price = ?, price_time = ?, provider = ? where symbol = ?",
                quote.currentPrice(),
                quote.priceTime(),
                quote.provider(),
                quote.symbol()
        );
        if (updatedRows > 0) {
            return;
        }

        BigDecimal previousClose = previousPrice == null ? quote.currentPrice() : previousPrice;
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values (?, ?, ?, ?, ?)
                """,
                quote.symbol(),
                quote.currentPrice(),
                previousClose,
                quote.priceTime(),
                quote.provider()
        );
    }

    private MarketPriceQuote normalizeQuote(String expectedSymbol, MarketPriceQuote quote) {
        if (quote == null) {
            throw new IllegalStateException("Market price provider returned empty quote");
        }
        String normalizedExpectedSymbol = normalizeSymbol(expectedSymbol);
        String normalizedQuoteSymbol = normalizeSymbol(quote.symbol());
        if (!normalizedExpectedSymbol.equals(normalizedQuoteSymbol)) {
            throw new IllegalStateException("Market price provider returned mismatched symbol: expected="
                    + normalizedExpectedSymbol + ", actual=" + normalizedQuoteSymbol);
        }
        if (quote.currentPrice() == null || quote.currentPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Market price provider returned non-positive price: symbol=" + normalizedExpectedSymbol);
        }
        if (quote.priceTime() == null) {
            throw new IllegalStateException("Market price provider returned empty price time: symbol=" + normalizedExpectedSymbol);
        }
        if (quote.provider() == null || quote.provider().isBlank()) {
            throw new IllegalStateException("Market price provider returned empty provider: symbol=" + normalizedExpectedSymbol);
        }
        return new MarketPriceQuote(
                normalizedExpectedSymbol,
                quote.currentPrice(),
                quote.provider().trim(),
                quote.priceTime()
        );
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private void insertPriceTick(MarketPriceQuote quote) {
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                values (?, ?, ?, ?, ?)
                """,
                quote.symbol(),
                quote.currentPrice(),
                quote.provider(),
                quote.priceTime(),
                quote.priceTime()
        );
    }

    private void publishPrice(MarketPriceQuote quote) {
        try {
            redisTemplate.opsForValue().set("stock:price:" + quote.symbol(), quote.currentPrice().toPlainString(), priceCacheTtl());
            redisTemplate.convertAndSend("stock.price." + quote.symbol(), serializePriceEvent(quote));
        } catch (RuntimeException ex) {
            log.debug("Redis price publish skipped: symbol={}, reason={}", quote.symbol(), ex.getMessage());
        }
    }

    private String serializePriceEvent(MarketPriceQuote quote) {
        try {
            return objectMapper.writeValueAsString(new PriceEvent(
                    quote.symbol(),
                    quote.currentPrice(),
                    quote.priceTime().toString(),
                    quote.provider()
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize price event", ex);
        }
    }

    private Duration priceCacheTtl() {
        return Duration.ofSeconds(Math.max(1, priceCacheTtlSeconds));
    }

    private record PriceRow(String symbol, BigDecimal currentPrice, BigDecimal referencePrice) {
    }

    private record PriceEvent(String symbol, BigDecimal currentPrice, String priceTime, String provider) {
    }
}
