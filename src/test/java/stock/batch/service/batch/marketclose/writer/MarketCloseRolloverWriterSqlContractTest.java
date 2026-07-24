package stock.batch.service.batch.marketclose.writer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCloseRolloverWriterSqlContractTest {

    private static final Path WRITER_SOURCE = Path.of(
            "src/main/java/stock/batch/service/batch/marketclose/writer/MarketCloseRolloverWriter.java"
    );

    @Test
    void marketCloseSql_symbolScopedPathsAvoidNullablePredicatesOnVolumeTables() throws Exception {
        String source = Files.readString(WRITER_SOURCE, StandardCharsets.UTF_8);
        String symbolReportPath = source.substring(
                source.indexOf("public int snapshotOrderBookDailySymbols("),
                source.indexOf("public int deleteOrderBookDailySnapshot(")
        );

        assertThat(source)
                .doesNotContain("? is null or")
                .doesNotContain("limit ?\n                 for update")
                .contains("where id in (:orderIds)\n                         order by id asc\n                         for update")
                .contains("lockAccountsForUpdate")
                .contains("lockSellHoldingsForUpdate")
                .contains("and execution.symbol = :symbol")
                .contains("and holding.symbol = :symbol")
                .contains("and h.symbol = :symbol")
                .contains("where i.symbol = :symbol")
                .contains("idx_stock_execution_source_symbol_time")
                .contains("idx_stock_execution_candle")
                .contains("idx_stock_execution_market_report_flow");
        assertThat(symbolReportPath)
                .doesNotContain("row_number() over");
    }

    @Test
    void fullCloseUsesOnlyBoundedExactLockAndMutationMethods() throws Exception {
        String source = Files.readString(WRITER_SOURCE, StandardCharsets.UTF_8);

        assertThat(source)
                .contains("public int captureOpenOrdersChunk(")
                .contains("force index (idx_stock_order_market_status_symbol)")
                .contains("stock_order force index (primary)")
                .contains("order by status asc, symbol asc, id asc")
                .contains("source_order_status = ?")
                .contains("and status = :sourceOrderStatus")
                .contains("and symbol = :symbol")
                .contains("where close_cycle_id = ?\n                           and released_at is null")
                .contains("public int cancelCapturedOrders(\n            long closeCycleId,\n            List<Long> orderIds,")
                .contains("update %s\n                           set status = 'CANCELLED'")
                .contains("update %s\n                   set status = 'CANCELLED'")
                .contains("public int creditCashChunk(Map<Long, BigDecimal> cashByAccountId,")
                .contains("public int releaseReservedSellQuantityChunk(\n            Map<HoldingReservationKey, Long>")
                .doesNotContain("creditCapturedBuyReservations")
                .doesNotContain("releaseCapturedSellReservations")
                .doesNotContain("public int captureOpenOrders(")
                .doesNotContain("public int snapshotHoldings(")
                .doesNotContain("public int cancelCapturedOrders(long closeCycleId, LocalDateTime")
                .doesNotContain("public void completeSnapshotReconciliation(");
    }

    @Test
    void accountReportClassification_usesFrozenSnapshotInsteadOfMutableParticipantConfiguration()
            throws Exception {
        String source = Files.readString(WRITER_SOURCE, StandardCharsets.UTF_8);
        String method = source.substring(
                source.indexOf("public int snapshotDailyAccountExecutions("),
                source.indexOf("public int deleteDailyAccountExecutionSnapshot(")
        );

        assertThat(method)
                .contains("join stock_close_account_snapshot account_snapshot")
                .contains("account_snapshot.close_cycle_id = :closeCycleId")
                .contains("account_snapshot.participant_category")
                .doesNotContain("stock_auto_participant")
                .doesNotContain("stock_listing_auto_account_config")
                .doesNotContain("join stock_account");
    }

    @Test
    void accountProfitSummaryRebuild_usesOneBoundedTradeDateRangeOutsideTheExecutionHotPath()
            throws Exception {
        String source = Files.readString(WRITER_SOURCE, StandardCharsets.UTF_8);
        String method = source.substring(
                source.indexOf("public int rebuildExecutionAccountDaySummary("),
                source.indexOf("public long countHoldingSnapshots(")
        );

        assertThat(method)
                .contains("from stock_execution execution")
                .contains("execution.executed_at >= ?")
                .contains("execution.executed_at < ?")
                .contains("group by execution.account_id")
                .contains("sum(coalesce(execution.realized_profit, 0))")
                .doesNotContain("stock_execution_daily_account_snapshot")
                .doesNotContain("date(execution.executed_at)")
                .doesNotContain("time(execution.executed_at)");
    }

    @Test
    void mysqlExecutionIndexHints_followTableAliases() throws Exception {
        String source = Files.readString(WRITER_SOURCE, StandardCharsets.UTF_8);

        assertThat(source)
                .contains("from stock_execution opening_execution %s")
                .contains("from stock_execution closing_execution %s")
                .contains("from stock_execution execution %s")
                .doesNotContain("from %s opening_execution")
                .doesNotContain("from %s closing_execution")
                .doesNotContain("from %s execution");
    }
}
