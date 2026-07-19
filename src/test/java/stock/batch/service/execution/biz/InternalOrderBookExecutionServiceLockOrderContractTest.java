package stock.batch.service.execution.biz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalOrderBookExecutionServiceLockOrderContractTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/stock/batch/service/execution/biz/InternalOrderBookExecutionService.java"
    );
    private static final Path READER_SOURCE = Path.of(
            "src/main/java/stock/batch/service/batch/execution/reader/OrderBookExecutionReader.java"
    );

    @Test
    void execution_discoversCandidateOutsideTransactionAndLocksOnlyAfterFence() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String retryMethod = source.substring(
                source.indexOf("private boolean matchNextWithRetry(String symbol)"),
                source.indexOf("private Optional<OrderBookMatchCandidate> findMatchCandidate")
        );
        String mutationMethod = source.substring(
                source.indexOf("private boolean matchSelectedCandidate"),
                source.indexOf("private OrderBookOrderRow findLockedOrder")
        );

        assertThat(retryMethod.indexOf("findMatchCandidate(symbol)"))
                .isPositive()
                .isLessThan(retryMethod.indexOf("transactionTemplate.execute"));
        assertThat(mutationMethod.indexOf("lockOpenOrderBookFences"))
                .isPositive()
                .isLessThan(mutationMethod.indexOf("lockAccountsForUpdate"));
        assertThat(mutationMethod.indexOf("lockAccountsForUpdate"))
                .isLessThan(mutationMethod.indexOf("findHoldingForUpdate"));
        assertThat(mutationMethod.indexOf("findHoldingForUpdate"))
                .isLessThan(mutationMethod.indexOf("findMatchOrdersForUpdate"));
    }

    @Test
    void execution_zeroMatch_doesNotImmediatelyRequeryOrRequeueSymbol() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String pollingMethod = source.substring(
                source.indexOf("private int executePolledSymbol"),
                source.indexOf("private void requeueIfStillExecutable")
        );

        assertThat(pollingMethod).contains(
                "if (matchCount > 0)",
                "requeueIfStillExecutable(symbol)"
        );
        assertThat(pollingMethod.indexOf("if (matchCount > 0)"))
                .isLessThan(pollingMethod.indexOf("requeueIfStillExecutable(symbol)"));
    }

    @Test
    void execution_exactOrderLocks_usePrimaryIndexWithoutSkipLocked() throws IOException {
        String source = Files.readString(READER_SOURCE, StandardCharsets.UTF_8);
        String constructor = source.substring(
                source.indexOf("public OrderBookExecutionReader"),
                source.indexOf("public List<String> findExecutableSymbolCandidates")
        );
        String lockMethod = source.substring(
                source.indexOf("public List<OrderBookOrderRow> findMatchOrdersForUpdate"),
                source.indexOf("private boolean isMySql")
        );

        assertThat(constructor).contains("stock_order force index (primary)");
        assertThat(lockMethod)
                .contains("where id in (:buyOrderId, :sellOrderId)", "order by id asc", "for update")
                .doesNotContain("skip locked");
    }
}
