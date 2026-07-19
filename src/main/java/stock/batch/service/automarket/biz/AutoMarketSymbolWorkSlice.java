package stock.batch.service.automarket.biz;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

final class AutoMarketSymbolWorkSlice {

    private static final long SLICE_WINDOW_SECONDS = 10L;

    private AutoMarketSymbolWorkSlice() {
    }

    static <T> List<T> select(
            List<T> items,
            Function<T, String> symbolExtractor,
            int limit,
            Instant now
    ) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<T> ordered = items.stream()
                .sorted(Comparator.comparing(item -> normalizedSymbol(symbolExtractor.apply(item))))
                .toList();
        if (ordered.size() <= limit) {
            return ordered;
        }
        int sliceCount = Math.ceilDiv(ordered.size(), limit);
        long timeBucket = now.getEpochSecond() / SLICE_WINDOW_SECONDS;
        int sliceIndex = (int) Math.floorMod(timeBucket, sliceCount);
        int start = sliceIndex * limit;
        int end = Math.min(ordered.size(), start + limit);
        return List.copyOf(ordered.subList(start, end));
    }

    private static String normalizedSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
