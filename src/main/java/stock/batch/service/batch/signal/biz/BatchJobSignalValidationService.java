package stock.batch.service.batch.signal.biz;

import java.time.LocalDate;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.signal.model.BatchJobSignal;

@Service
@RequiredArgsConstructor
public class BatchJobSignalValidationService {

    private static final String SYMBOL_CLOSE_SIGNAL = "MARKET_CLOSE_ROLLOVER_SYMBOL";
    private static final String SYMBOL_CANCEL_SIGNAL = "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL";

    private final JdbcClient jdbcClient;

    @Transactional(readOnly = true)
    public void validate(BatchJobSignal signal) {
        if (signal.requestedBusinessDate() == null) {
            throw new IllegalArgumentException("Signal requested business date is missing: id=" + signal.id());
        }
        SignalValidationRow row = loadContext(signal);
        if (!signal.requestedBusinessDate().equals(row.activeBusinessDate())) {
            throw new IllegalArgumentException(
                    "Signal business date is stale: id=%d, requested=%s, active=%s"
                            .formatted(signal.id(), signal.requestedBusinessDate(), row.activeBusinessDate())
            );
        }
        if (isSymbolScoped(signal)) {
            if (signal.requestedSessionEpoch() == null) {
                throw new IllegalArgumentException("Symbol signal session epoch is missing: id=" + signal.id());
            }
            if ("OPEN".equals(row.fenceSessionState())) {
                throw new IllegalArgumentException(
                        "Symbol signal requires a halted or closed market: id=%d, symbol=%s"
                                .formatted(signal.id(), signal.symbol())
                );
            }
            if (!matchesRequestedOrOwnedCloseEpoch(signal, row)) {
                throw new IllegalArgumentException(
                        "Symbol signal session fence is stale: id=%d, symbol=%s"
                                .formatted(signal.id(), signal.symbol())
                );
            }
        }
        if (signal.expectedCycleId() != null && !signal.expectedCycleId().equals(row.expectedCycleId())) {
            throw new IllegalArgumentException("Signal post-close cycle does not match its request context: id=" + signal.id());
        }
    }

    private SignalValidationRow loadContext(BatchJobSignal signal) {
        boolean symbolScoped = isSymbolScoped(signal);
        String scopeType = symbolScoped ? "SYMBOL" : "FULL_MARKET";
        String scopeKey = symbolScoped ? normalizeSymbol(signal) : "ALL";
        return jdbcClient.sql(
                        """
                        select state.active_business_date,
                               fence.business_date as fence_business_date,
                               fence.session_epoch as fence_session_epoch,
                               fence.session_state as fence_session_state,
                               cycle.phase as cycle_phase,
                               cycle.status as cycle_status,
                               cycle.id as expected_cycle_id
                          from stock_market_business_state state
                          left join stock_market_session_fence fence
                            on fence.market_type = 'ORDER_BOOK'
                           and fence.symbol = :symbol
                          left join stock_post_close_cycle cycle
                            on cycle.business_date = :requestedBusinessDate
                           and cycle.scope_type = :scopeType
                           and cycle.scope_key = :scopeKey
                         where state.state_id = 'DEFAULT'
                        """
                )
                .param("symbol", signal.symbol() == null ? "" : normalizeSymbol(signal))
                .param("requestedBusinessDate", signal.requestedBusinessDate())
                .param("scopeType", scopeType)
                .param("scopeKey", scopeKey)
                .query((rs, rowNum) -> new SignalValidationRow(
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("fence_business_date", LocalDate.class),
                        rs.getObject("fence_session_epoch", Long.class),
                        rs.getString("fence_session_state"),
                        rs.getString("cycle_phase"),
                        rs.getString("cycle_status"),
                        rs.getObject("expected_cycle_id", Long.class)
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Active market business state is missing"));
    }

    /**
     * A symbol-close command is allowed to resume after its first attempt has already advanced
     * the fence exactly once from OPEN to CLOSING/CLOSED. Without this narrow recovery rule, a
     * transient Redis/DB failure after the fence commit permanently invalidates the signal that
     * owns the same close cycle. More than one epoch transition is always treated as stale.
     */
    private boolean matchesRequestedOrOwnedCloseEpoch(BatchJobSignal signal, SignalValidationRow row) {
        if (!signal.requestedBusinessDate().equals(row.fenceBusinessDate())
                || row.fenceSessionEpoch() == null) {
            return false;
        }
        long requestedEpoch = signal.requestedSessionEpoch();
        long currentEpoch = row.fenceSessionEpoch();
        if (requestedEpoch == currentEpoch) {
            return true;
        }
        if (requestedEpoch == Long.MAX_VALUE
                || currentEpoch != requestedEpoch + 1
                || !("CLOSING".equals(row.fenceSessionState()) || "CLOSED".equals(row.fenceSessionState()))) {
            return false;
        }
        if (SYMBOL_CANCEL_SIGNAL.equals(signal.signalType())) {
            return true;
        }
        return SYMBOL_CLOSE_SIGNAL.equals(signal.signalType())
                && signal.expectedCycleId() != null
                && signal.expectedCycleId().equals(row.expectedCycleId())
                && row.cyclePhase() != null
                && row.cycleStatus() != null
                && (!("CLOSE_REQUESTED".equals(row.cyclePhase()) && "PENDING".equals(row.cycleStatus())));
    }

    private boolean isSymbolScoped(BatchJobSignal signal) {
        return SYMBOL_CLOSE_SIGNAL.equals(signal.signalType()) || SYMBOL_CANCEL_SIGNAL.equals(signal.signalType());
    }

    private String normalizeSymbol(BatchJobSignal signal) {
        if (signal.symbol() == null || signal.symbol().isBlank()) {
            throw new IllegalArgumentException("Signal symbol is missing: id=" + signal.id());
        }
        return signal.symbol().trim().toUpperCase(Locale.ROOT);
    }

    private record SignalValidationRow(
            LocalDate activeBusinessDate,
            LocalDate fenceBusinessDate,
            Long fenceSessionEpoch,
            String fenceSessionState,
            String cyclePhase,
            String cycleStatus,
            Long expectedCycleId
    ) {
    }
}
