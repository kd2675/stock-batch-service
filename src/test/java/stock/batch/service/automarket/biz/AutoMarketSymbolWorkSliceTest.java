package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class AutoMarketSymbolWorkSliceTest {

    @Test
    void select_moreItemsThanLimit_rotatesDeterministicBoundedSlices() {
        List<String> symbols = IntStream.rangeClosed(1, 250)
                .mapToObj(index -> "STOCK%03d".formatted(index))
                .toList();

        String result = AutoMarketSymbolWorkSlice.select(symbols, symbol -> symbol, 100, Instant.ofEpochSecond(0)).size()
                + ":" + AutoMarketSymbolWorkSlice.select(symbols, symbol -> symbol, 100, Instant.ofEpochSecond(10)).getFirst()
                + ":" + AutoMarketSymbolWorkSlice.select(symbols, symbol -> symbol, 100, Instant.ofEpochSecond(20)).size()
                + ":" + AutoMarketSymbolWorkSlice.select(symbols, symbol -> symbol, 100, Instant.ofEpochSecond(30)).getFirst();

        assertThat(result).isEqualTo("100:STOCK101:50:STOCK001");
    }
}
