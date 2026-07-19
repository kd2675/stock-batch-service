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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BatchJobSignalReaderTest {

    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 15, 0, 10);
    private static final LocalDateTime SYSTEM_NOW = LocalDateTime.now();

    @Autowired
    private BatchJobSignalReader signalReader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_batch_job_signal");
        jdbcTemplate.update("delete from stock_post_close_phase_attempt");
        jdbcTemplate.update("delete from stock_post_close_cycle");
    }

    @Test
    void claimNext_futureEligibleHeadSignal_claimsLaterEligibleSignal() {
        insertSignal(10L, "DEFERRED", SIMULATION_NOW.plusHours(1), SYSTEM_NOW.minusMinutes(1), 1, 12, null, null);
        insertSignal(11L, "PENDING", SIMULATION_NOW, SYSTEM_NOW, 0, 12, null, null);

        BatchJobSignal claimed = signalReader.claimNext(SIMULATION_NOW).orElseThrow();

        assertThat(claimed)
                .extracting(BatchJobSignal::id, BatchJobSignal::attemptCount, BatchJobSignal::claimToken)
                .satisfies(values -> {
                    assertThat(values.get(0)).isEqualTo(11L);
                    assertThat(values.get(1)).isEqualTo(1);
                    assertThat(values.get(2)).asString().isNotBlank();
                });
    }

    @Test
    void hasClaimable_emptyQueue_returnsFalseWithoutClaimTransaction() {
        assertThat(signalReader.hasClaimable(SIMULATION_NOW)).isFalse();
    }

    @Test
    void claimNext_expiredProcessingLease_reclaimsWithNewAttempt() {
        insertSignal(
                20L,
                "PROCESSING",
                SIMULATION_NOW,
                SYSTEM_NOW.minusMinutes(1),
                1,
                12,
                "old-token",
                SYSTEM_NOW.minusSeconds(1)
        );

        BatchJobSignal claimed = signalReader.claimNext(SIMULATION_NOW).orElseThrow();

        assertThat(claimed)
                .extracting(BatchJobSignal::id, BatchJobSignal::attemptCount, BatchJobSignal::claimToken)
                .satisfies(values -> {
                    assertThat(values.get(0)).isEqualTo(20L);
                    assertThat(values.get(1)).isEqualTo(2);
                    assertThat(values.get(2)).isNotEqualTo("old-token");
                });
    }

    @Test
    void deadLetterExhaustedLeases_maxAttemptsReached_marksTerminal() {
        insertSignal(
                30L,
                "PROCESSING",
                SIMULATION_NOW,
                SYSTEM_NOW.minusMinutes(1),
                3,
                3,
                "expired-token",
                SYSTEM_NOW.minusSeconds(1)
        );

        int updated = signalReader.deadLetterExhaustedLeases(SYSTEM_NOW);

        assertThat(jdbcTemplate.queryForMap(
                "select status, failure_class from stock_batch_job_signal where id = 30"
        )).containsEntry("status", "DEAD_LETTER")
                .containsEntry("failure_class", "LEASE_EXHAUSTED");
        assertThat(updated).isOne();
    }

    @Test
    void claimNext_manualCash_waitsForFrozenPortfolioSettlementWithoutConsumingAttempt() {
        insertManualCashSignal(40L, LocalDate.of(2026, 7, 14));
        insertCycle(400L, LocalDate.of(2026, 7, 14), "LEDGER_FROZEN");

        assertThat(signalReader.hasClaimable(SIMULATION_NOW)).isFalse();
        assertThat(signalReader.claimNext(SIMULATION_NOW)).isEmpty();
        assertThat(queryAttemptCount(40L)).isZero();

        jdbcTemplate.update(
                "update stock_post_close_cycle set phase = 'PORTFOLIO_SETTLED' where id = 400"
        );

        assertThat(signalReader.hasClaimable(SIMULATION_NOW)).isTrue();
        assertThat(signalReader.claimNext(SIMULATION_NOW)).get()
                .extracting(BatchJobSignal::id, BatchJobSignal::attemptCount)
                .containsExactly(40L, 1);
    }

    private void insertSignal(
            long id,
            String status,
            LocalDateTime eligibleAt,
            LocalDateTime nextAttemptAt,
            int attemptCount,
            int maxAttempts,
            String claimToken,
            LocalDateTime leaseUntil
    ) {
        jdbcTemplate.update(
                """
                insert into stock_batch_job_signal(
                    id, signal_type, job_name, execution_mode, symbol, payload_json,
                    status, requested_by, requested_at, requested_business_date,
                    requested_session_epoch, expected_cycle_id, eligible_at, next_attempt_at,
                    attempt_count, max_attempts, claim_token, lease_until, failure_class,
                    picked_at, completed_at, processed_count, message, error_message,
                    created_at, updated_at
                )
                values (?, 'MARKET_CLOSE_ROLLOVER_RUN', 'market-close-rollover', 'manual-rollover',
                        null, null, ?, 'admin', ?, ?, null, null, ?, ?, ?, ?, ?, ?, null,
                        null, null, null, null, null, ?, ?)
                """,
                id,
                status,
                SYSTEM_NOW.minusMinutes(2),
                LocalDate.of(2026, 7, 14),
                eligibleAt,
                nextAttemptAt,
                attemptCount,
                maxAttempts,
                claimToken,
                leaseUntil,
                SYSTEM_NOW.minusMinutes(2),
                SYSTEM_NOW.minusMinutes(2)
        );
    }

    private void insertManualCashSignal(long id, LocalDate businessDate) {
        jdbcTemplate.update(
                """
                insert into stock_batch_job_signal(
                    id, signal_type, job_name, execution_mode, symbol, payload_json,
                    status, requested_by, requested_at, requested_business_date,
                    requested_session_epoch, expected_cycle_id, eligible_at, next_attempt_at,
                    attempt_count, max_attempts, claim_token, lease_until, failure_class,
                    picked_at, completed_at, processed_count, message, error_message,
                    created_at, updated_at
                )
                values (?, 'AUTO_PARTICIPANT_CASH_FLOW_RUN', 'auto-participant-cash-flow',
                        'manual-recurring-cash', null, null, 'PENDING', 'admin', ?, ?,
                        null, null, ?, ?, 0, 12, null, null, null,
                        null, null, null, null, null, ?, ?)
                """,
                id,
                SYSTEM_NOW.minusMinutes(2),
                businessDate,
                SIMULATION_NOW.minusMinutes(1),
                SYSTEM_NOW.minusMinutes(1),
                SYSTEM_NOW.minusMinutes(2),
                SYSTEM_NOW.minusMinutes(2)
        );
    }

    private void insertCycle(long id, LocalDate businessDate, String phase) {
        jdbcTemplate.update(
                """
                insert into stock_post_close_cycle(
                    id, business_date, scope_type, scope_key, cycle_kind, phase, status,
                    phase_revision, version, attempt_count, created_at, updated_at
                )
                values (?, ?, 'FULL_MARKET', 'ALL', 'TRADING', ?, 'PENDING', 1, 0, 0, ?, ?)
                """,
                id,
                businessDate,
                phase,
                SYSTEM_NOW.minusMinutes(2),
                SYSTEM_NOW.minusMinutes(2)
        );
    }

    private int queryAttemptCount(long signalId) {
        Integer count = jdbcTemplate.queryForObject(
                "select attempt_count from stock_batch_job_signal where id = ?",
                Integer.class,
                signalId
        );
        return count == null ? 0 : count;
    }
}
