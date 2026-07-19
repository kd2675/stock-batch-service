package stock.batch.service.batch.signal.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import stock.batch.service.batch.signal.model.BatchJobSignal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BatchJobSignalValidationServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDateTime NOW = BUSINESS_DATE.atTime(18, 30);

    @Autowired
    private BatchJobSignalValidationService validationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_post_close_phase_attempt");
        jdbcTemplate.update("delete from stock_post_close_cycle");
        jdbcTemplate.update("delete from stock_market_session_fence");
        jdbcTemplate.update("delete from stock_market_business_state");
        jdbcTemplate.update(
                """
                insert into stock_market_business_state(
                    state_id, active_business_date, preparing_business_date,
                    raw_simulation_date, version, created_at, updated_at
                ) values ('DEFAULT', ?, null, ?, 0, ?, ?)
                """,
                BUSINESS_DATE,
                BUSINESS_DATE,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state,
                    state_changed_at, version, created_at, updated_at
                ) values ('ORDER_BOOK', 'DEMO001', ?, 11, 'CLOSED', ?, 0, ?, ?)
                """,
                BUSINESS_DATE,
                NOW,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    id, business_date, scope_type, scope_key, phase, status,
                    phase_revision, version, attempt_count, created_at, updated_at
                ) values (801, ?, 'SYMBOL', 'DEMO001', 'CLOSE_REQUESTED', 'PENDING', 1, 0, 0, ?, ?)
                """,
                BUSINESS_DATE,
                NOW,
                NOW
        );
    }

    @Test
    void validate_matchingBusinessDateEpochAndCycle_allowsSignal() {
        BatchJobSignal signal = signal(11L, 801L);

        assertThatCode(() -> validationService.validate(signal)).doesNotThrowAnyException();
    }

    @Test
    void validate_staleEpoch_rejectsSignalBeforeLaunchingJob() {
        BatchJobSignal signal = signal(10L, 801L);

        assertThatThrownBy(() -> validationService.validate(signal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session fence is stale");
    }

    @Test
    void validate_closeAttemptAdvancedFenceOnce_allowsSameCycleRecovery() {
        jdbcTemplate.update(
                "update stock_market_session_fence set session_epoch = 12, session_state = 'CLOSING' where symbol = 'DEMO001'"
        );
        jdbcTemplate.update(
                "update stock_post_close_cycle set status = 'FAILED' where id = 801"
        );
        BatchJobSignal signal = signal(11L, 801L);

        assertThatCode(() -> validationService.validate(signal)).doesNotThrowAnyException();
    }

    @Test
    void validate_closeAttemptAdvancedFenceMoreThanOnce_rejectsStaleSignal() {
        jdbcTemplate.update(
                "update stock_market_session_fence set session_epoch = 13, session_state = 'CLOSED' where symbol = 'DEMO001'"
        );
        BatchJobSignal signal = signal(11L, 801L);

        assertThatThrownBy(() -> validationService.validate(signal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session fence is stale");
    }

    @Test
    void validate_openSymbol_rejectsSignalBeforeLaunchingJob() {
        jdbcTemplate.update(
                "update stock_market_session_fence set session_state = 'OPEN' where symbol = 'DEMO001'"
        );
        BatchJobSignal signal = signal(11L, 801L);

        assertThatThrownBy(() -> validationService.validate(signal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a halted or closed market");
    }

    private BatchJobSignal signal(Long epoch, Long cycleId) {
        return new BatchJobSignal(
                91L,
                "MARKET_CLOSE_ROLLOVER_SYMBOL",
                "market-close-rollover",
                "price-limit-base:DEMO001",
                "DEMO001",
                "admin",
                NOW,
                BUSINESS_DATE,
                epoch,
                cycleId,
                NOW,
                NOW,
                1,
                12,
                "claim-91",
                NOW.plusMinutes(3)
        );
    }
}
