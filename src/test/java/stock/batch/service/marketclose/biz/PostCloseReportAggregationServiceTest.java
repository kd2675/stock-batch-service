package stock.batch.service.marketclose.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleKind;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;

class PostCloseReportAggregationServiceTest {

    @Test
    void aggregateOrderBookDailySnapshots_openMarket_rejectsBeforeCycleOrLedgerQueries() {
        PostCloseCycleService cycleService = mock(PostCloseCycleService.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        PostCloseReportAggregationUnitService unitService = mock(PostCloseReportAggregationUnitService.class);
        PostCloseReportAggregationService service = new PostCloseReportAggregationService(
                cycleService,
                fenceService,
                writer,
                unitService
        );
        when(fenceService.hasOpenMarket()).thenReturn(true);

        assertThatThrownBy(() -> service.aggregateOrderBookDailySnapshotChunk(
                10L,
                LocalDateTime.of(2026, 7, 16, 0, 10),
                ""
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("market is open");

        verify(cycleService, never()).findById(10L);
        verify(writer, never()).findCloseReportSymbolChunk(anyLong(), anyString(), anyInt());
        verifyNoInteractions(unitService);
    }

    @Test
    void aggregateOrderBookDailySnapshots_beforeCorporateCashPhase_rejectsBeforeLedgerQueries() {
        PostCloseCycleService cycleService = mock(PostCloseCycleService.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        PostCloseReportAggregationUnitService unitService = mock(PostCloseReportAggregationUnitService.class);
        PostCloseReportAggregationService service = new PostCloseReportAggregationService(
                cycleService,
                fenceService,
                writer,
                unitService
        );
        PostCloseCycle frozenCycle = new PostCloseCycle(
                10L,
                LocalDate.of(2026, 7, 15),
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostCloseCycleKind.TRADING,
                null,
                PostClosePhase.LEDGER_FROZEN,
                PostCloseCycleStatus.PENDING,
                0,
                3L,
                99L,
                LocalDateTime.of(2026, 7, 15, 18, 10),
                0,
                null,
                null,
                null
        );
        when(fenceService.hasOpenMarket()).thenReturn(false);
        when(cycleService.findById(10L)).thenReturn(Optional.of(frozenCycle));

        assertThatThrownBy(() -> service.aggregateOrderBookDailySnapshotChunk(
                10L,
                LocalDateTime.of(2026, 7, 16, 0, 10),
                ""
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires OVERNIGHT_CASH_APPLIED");

        verify(writer, never()).findCloseReportSymbolChunk(anyLong(), anyString(), anyInt());
        verifyNoInteractions(unitService);
    }

    @Test
    void aggregateOrderBookDailySnapshotChunk_exactCheckpointCohort_returnsDurableKeysetContinuation() {
        PostCloseCycleService cycleService = mock(PostCloseCycleService.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        PostCloseReportAggregationUnitService unitService = mock(PostCloseReportAggregationUnitService.class);
        PostCloseReportAggregationService service = new PostCloseReportAggregationService(
                cycleService,
                fenceService,
                writer,
                unitService
        );
        List<String> symbols = IntStream.range(0, 25)
                .mapToObj(index -> "S%03d".formatted(index))
                .toList();
        LocalDateTime aggregatedAt = LocalDateTime.of(2026, 7, 16, 0, 10);
        when(fenceService.hasOpenMarket()).thenReturn(false);
        when(cycleService.findById(10L)).thenReturn(Optional.of(overnightCashAppliedCycle()));
        when(writer.findCloseReportSymbolChunk(10L, "", 25)).thenReturn(symbols);
        when(unitService.aggregateSymbolReport(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(99L),
                anyString(),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 7, 15)),
                org.mockito.ArgumentMatchers.eq(aggregatedAt)
        )).thenReturn(1);

        PostCloseReportAggregationService.ReportAggregationChunk result =
                service.aggregateOrderBookDailySnapshotChunk(10L, aggregatedAt, "");

        assertThat(result.processedCount()).isEqualTo(25);
        assertThat(result.lastSymbol()).isEqualTo("S024");
        assertThat(result.finished()).isFalse();
        verify(writer).findCloseReportSymbolChunk(10L, "", 25);
    }

    @Test
    void aggregateAccountDailySnapshotChunk_afterCheckpointEmpty_finishesWithoutLedgerAggregation() {
        PostCloseCycleService cycleService = mock(PostCloseCycleService.class);
        MarketSessionFenceService fenceService = mock(MarketSessionFenceService.class);
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        PostCloseReportAggregationUnitService unitService = mock(PostCloseReportAggregationUnitService.class);
        PostCloseReportAggregationService service = new PostCloseReportAggregationService(
                cycleService,
                fenceService,
                writer,
                unitService
        );
        LocalDateTime aggregatedAt = LocalDateTime.of(2026, 7, 16, 0, 10);
        when(fenceService.hasOpenMarket()).thenReturn(false);
        when(cycleService.findById(10L)).thenReturn(Optional.of(overnightCashAppliedCycle()));
        when(writer.findCloseReportSymbolChunk(10L, "S024", 25)).thenReturn(List.of());

        PostCloseReportAggregationService.ReportAggregationChunk result =
                service.aggregateAccountDailySnapshotChunk(10L, aggregatedAt, "S024");

        assertThat(result.processedCount()).isZero();
        assertThat(result.lastSymbol()).isEqualTo("S024");
        assertThat(result.finished()).isTrue();
        verifyNoInteractions(unitService);
    }

    @Test
    void validateSymbolChunkSize_aboveVolumeLimit_rejectsConfiguration() {
        assertThatThrownBy(() -> PostCloseReportAggregationService.validateSymbolChunkSize(201))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 200");
    }

    private PostCloseCycle overnightCashAppliedCycle() {
        return new PostCloseCycle(
                10L,
                LocalDate.of(2026, 7, 15),
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostCloseCycleKind.TRADING,
                null,
                PostClosePhase.OVERNIGHT_CASH_APPLIED,
                PostCloseCycleStatus.RUNNING,
                0,
                3L,
                99L,
                LocalDateTime.of(2026, 7, 15, 18, 10),
                0,
                null,
                null,
                null
        );
    }
}
