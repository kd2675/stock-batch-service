package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkippedBusinessDateRecoveryServiceTest {

    private static final LocalDate ACTIVE_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalTime CLOSE_TIME = LocalTime.of(18, 0);

    @Mock
    private MarketSessionFenceService marketSessionFenceService;

    @Mock
    private PostCloseCycleService postCloseCycleService;

    private SkippedBusinessDateRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new SkippedBusinessDateRecoveryService(
                marketSessionFenceService,
                postCloseCycleService
        );
    }

    @Test
    void shouldSkip_respectsDateAndCloseBoundary() {
        LocalDate nextDate = ACTIVE_DATE.plusDays(1);

        assertThat(List.of(
                service.shouldSkip(nextDate, nextDate.atTime(17, 59, 59), CLOSE_TIME),
                service.shouldSkip(nextDate, nextDate.atTime(18, 0), CLOSE_TIME),
                service.shouldSkip(nextDate, nextDate.plusDays(1).atStartOfDay(), CLOSE_TIME)
        )).containsExactly(false, true, true);
    }

    @Test
    void skipPreparedBusinessDate_closedMarket_advancesFenceBeforeRecordingSkippedCycle() {
        LocalDate skippedDate = ACTIVE_DATE.plusDays(1);
        LocalDateTime now = skippedDate.atTime(18, 1);

        service.skipPreparedBusinessDate(ACTIVE_DATE, skippedDate, now, CLOSE_TIME);

        InOrder order = inOrder(marketSessionFenceService, postCloseCycleService);
        order.verify(marketSessionFenceService).advanceClosedBusinessDate(
                ACTIVE_DATE,
                skippedDate,
                skippedDate,
                now
        );
        order.verify(postCloseCycleService).ensureSkippedFullMarketCycle(
                skippedDate,
                "Raw simulation time passed before this business date could open",
                now
        );
        order.verify(postCloseCycleService).prepareSkippedCycleForNextOpen(skippedDate, now);
    }

    @Test
    void recoverNextMissedBusinessDate_completedActiveCycle_advancesOnlyOneDate() {
        LocalDate skippedDate = ACTIVE_DATE.plusDays(1);
        LocalDateTime now = ACTIVE_DATE.plusDays(3).atTime(5, 0);
        when(marketSessionFenceService.businessState()).thenReturn(
                new MarketSessionFenceService.MarketBusinessStateSnapshot(
                        ACTIVE_DATE,
                        skippedDate,
                        now.toLocalDate(),
                        3L
                )
        );
        when(postCloseCycleService.isCompleted(ACTIVE_DATE)).thenReturn(true);

        var recovered = service.recoverNextMissedBusinessDate(now, CLOSE_TIME);

        assertThat(recovered).contains(skippedDate);
        verify(marketSessionFenceService).advanceClosedBusinessDate(
                ACTIVE_DATE,
                skippedDate,
                now.toLocalDate(),
                now
        );
        verify(postCloseCycleService, never()).prepareSkippedCycleForNextOpen(skippedDate, now);
    }

    @Test
    void recoverNextMissedBusinessDate_lastMissedDate_preparesOnlyPreOpenSuffixForNextOpen() {
        LocalDate skippedDate = ACTIVE_DATE.plusDays(1);
        LocalDateTime now = skippedDate.plusDays(1).atTime(5, 0);
        when(marketSessionFenceService.businessState()).thenReturn(
                new MarketSessionFenceService.MarketBusinessStateSnapshot(
                        ACTIVE_DATE,
                        skippedDate,
                        now.toLocalDate(),
                        3L
                )
        );
        when(postCloseCycleService.isCompleted(ACTIVE_DATE)).thenReturn(true);

        var recovered = service.recoverNextMissedBusinessDate(now, CLOSE_TIME);

        assertThat(recovered).contains(skippedDate);
        verify(postCloseCycleService).prepareSkippedCycleForNextOpen(skippedDate, now);
    }

    @Test
    void recoverNextMissedBusinessDate_incompleteActiveCycle_doesNotAdvance() {
        LocalDateTime now = ACTIVE_DATE.plusDays(3).atTime(5, 0);
        when(marketSessionFenceService.businessState()).thenReturn(
                new MarketSessionFenceService.MarketBusinessStateSnapshot(
                        ACTIVE_DATE,
                        ACTIVE_DATE.plusDays(1),
                        now.toLocalDate(),
                        3L
                )
        );
        when(postCloseCycleService.isCompleted(ACTIVE_DATE)).thenReturn(false);

        var recovered = service.recoverNextMissedBusinessDate(now, CLOSE_TIME);

        assertThat(recovered).isEmpty();
        verify(marketSessionFenceService, never()).advanceClosedBusinessDate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
