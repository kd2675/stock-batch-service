package stock.batch.service.marketclose.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleKind;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PostCloseCycleServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 18, 0);

    @Autowired
    private PostCloseCycleService postCloseCycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_post_close_phase_attempt");
        jdbcTemplate.update("delete from stock_post_close_cycle");
    }

    @Test
    void ensureFullMarketCycle_sameBusinessDate_reusesOneLogicalCycle() {
        PostCloseCycle first = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        PostCloseCycle second = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW.plusSeconds(1));

        assertThat(List.of(first.id(), second.id(), cycleCount()))
                .containsExactly(first.id(), first.id(), 1L);
    }

    @Test
    void tryClaim_activeLease_allowsOnlyOneOwnerAttempt() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);

        PostClosePhaseClaim first = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW)
                .orElseThrow();

        assertThat(List.<Object>of(
                first.attemptNo(),
                postCloseCycleService.tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(1)).isPresent(),
                queryLong("select count(*) from stock_post_close_phase_attempt where status = 'RUNNING'")
        )).containsExactly(1, false, 1L);
    }

    @Test
    void tryClaim_expiredLease_abandonsOldAttemptAndCreatesNextAttempt() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        postCloseCycleService.tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW).orElseThrow();
        jdbcTemplate.update(
                "update stock_post_close_cycle set lease_until = ? where id = ?",
                NOW.minusSeconds(1),
                cycle.id()
        );

        PostClosePhaseClaim second = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(1))
                .orElseThrow();

        assertThat(List.of(
                second.attemptNo(),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 1"),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 2")
        )).containsExactly(2, "ABANDONED", "RUNNING");
    }

    @Test
    void renewOwnedRunningLease_activeClaim_extendsLeaseWithoutCreatingAttempt() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        postCloseCycleService.tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW).orElseThrow();
        LocalDateTime heartbeatAt = NOW.plusSeconds(30);

        boolean renewed = postCloseCycleService.renewOwnedRunningLease(cycle.id(), heartbeatAt);

        assertThat(List.<Object>of(
                renewed,
                jdbcTemplate.queryForObject(
                        "select lease_until from stock_post_close_cycle where id = ?",
                        LocalDateTime.class,
                        cycle.id()
                ),
                queryLong("select count(*) from stock_post_close_phase_attempt")
        )).containsExactly(true, heartbeatAt.plusSeconds(postCloseCycleService.leaseSeconds()), 1L);
    }

    @Test
    void renewOwnedRunningLease_otherOwnerRunning_rejectsLostOwnership() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        postCloseCycleService.tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW).orElseThrow();
        jdbcTemplate.update(
                "update stock_post_close_cycle set owner_id = 'other-node' where id = ?",
                cycle.id()
        );

        assertThatThrownBy(() -> postCloseCycleService.renewOwnedRunningLease(cycle.id(), NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease ownership was lost");
    }

    @Test
    void linkOwnedBatchJobExecution_runningCoordinatorPhase_recordsPhysicalExecutionId() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        postCloseCycleService.tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW).orElseThrow();

        boolean linked = postCloseCycleService.linkOwnedBatchJobExecution(
                cycle.id(),
                8_001L,
                NOW.plusSeconds(1)
        );

        assertThat(List.<Object>of(
                linked,
                queryLong("select batch_job_execution_id from stock_post_close_phase_attempt where attempt_no = 1")
        )).containsExactly(true, 8_001L);
    }

    @Test
    void linkOwnedBatchJobExecution_unclaimedCycle_skipsControlTableWrite() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);

        boolean linked = postCloseCycleService.linkOwnedBatchJobExecution(
                cycle.id(),
                8_002L,
                NOW.plusSeconds(1)
        );

        assertThat(List.<Object>of(linked, queryLong("select count(*) from stock_post_close_phase_attempt")))
                .containsExactly(false, 0L);
    }

    @Test
    void completePhase_validClaim_advancesCycleAndCompletesAttemptAtomically() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        PostClosePhaseClaim claim = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW)
                .orElseThrow();
        LocalDateTime eligibleAt = NOW.plusMinutes(10);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> postCloseCycleService.completePhase(
                claim,
                PostClosePhase.LEDGER_FROZEN,
                991L,
                eligibleAt,
                NOW.plusSeconds(2)
        ));

        PostCloseCycle completed = postCloseCycleService.findById(cycle.id()).orElseThrow();
        assertThat(List.of(
                completed.phase(),
                completed.status(),
                completed.closeRunId(),
                completed.settlementEligibleAt(),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 1")
        )).containsExactly(
                PostClosePhase.LEDGER_FROZEN,
                PostCloseCycleStatus.PENDING,
                991L,
                eligibleAt,
                "COMPLETED"
        );
    }

    @Test
    void failPhase_retry_incrementsAttemptAndPreservesFailureHistory() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        PostClosePhaseClaim first = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW)
                .orElseThrow();
        postCloseCycleService.failPhase(first, new IllegalStateException("freeze failed"), NOW.plusSeconds(1));

        Optional<PostClosePhaseClaim> prematureRetry = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(30));
        PostClosePhaseClaim retry = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(31))
                .orElseThrow();
        postCloseCycleService.failPhase(
                retry,
                new IllegalStateException("freeze failed again"),
                NOW.plusSeconds(32)
        );
        PostCloseCycle failedTwice = postCloseCycleService.findById(cycle.id()).orElseThrow();

        assertThat(List.<Object>of(
                prematureRetry.isPresent(),
                retry.attemptNo(),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 1"),
                queryString("select error_code from stock_post_close_phase_attempt where attempt_no = 1"),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 2"),
                failedTwice.phaseRevision(),
                failedTwice.nextRetryAt()
        )).containsExactly(
                false,
                2,
                "FAILED",
                "IllegalStateException",
                "FAILED",
                1,
                NOW.plusSeconds(92)
        );
    }

    @Test
    void deferPhase_setsDurableRetryWindowInsteadOfPollingJobEveryCycle() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        PostClosePhaseClaim first = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW)
                .orElseThrow();
        postCloseCycleService.deferPhase(first, "runtime control disabled", NOW.plusSeconds(1));

        Optional<PostClosePhaseClaim> prematureRetry = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(60));
        PostClosePhaseClaim retry = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(61))
                .orElseThrow();
        PostCloseCycle retried = postCloseCycleService.findById(cycle.id()).orElseThrow();

        assertThat(List.<Object>of(
                prematureRetry.isPresent(),
                retry.attemptNo(),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 1"),
                queryString("select error_code from stock_post_close_phase_attempt where attempt_no = 1"),
                retried.phaseRevision()
        )).containsExactly(false, 2, "ABANDONED", "DEFERRED", 1);
    }

    @Test
    void continuePhaseAfterProgress_retriesAtBaseDelayWithoutIncreasingFailureExponent() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        PostClosePhaseClaim progressClaim = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW)
                .orElseThrow();
        postCloseCycleService.continuePhaseAfterProgress(
                progressClaim,
                200,
                "more bounded work remains",
                NOW.plusSeconds(1)
        );

        PostCloseCycle continued = postCloseCycleService.findById(cycle.id()).orElseThrow();
        PostClosePhaseClaim retry = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW.plusSeconds(31))
                .orElseThrow();
        postCloseCycleService.failPhase(
                retry,
                new IllegalStateException("first no-progress failure"),
                NOW.plusSeconds(32)
        );
        PostCloseCycle failed = postCloseCycleService.findById(cycle.id()).orElseThrow();

        assertThat(List.<Object>of(
                continued.status(),
                continued.nextRetryAt(),
                queryString("select status from stock_post_close_phase_attempt where attempt_no = 1"),
                queryString("select error_code from stock_post_close_phase_attempt where attempt_no = 1"),
                failed.phaseRevision(),
                failed.nextRetryAt()
        )).containsExactly(
                PostCloseCycleStatus.DEFERRED,
                NOW.plusSeconds(31),
                "ABANDONED",
                "BOUNDED_PROGRESS",
                1,
                NOW.plusSeconds(62)
        );
    }

    @Test
    void failPhase_lateCycleFirstFailure_startsAtBaseDelayInsteadOfGlobalAttemptCount() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set attempt_count = 8 where id = ?",
                cycle.id()
        );
        PostClosePhaseClaim latePhaseClaim = postCloseCycleService
                .tryClaim(cycle.id(), PostClosePhase.CLOSE_REQUESTED, NOW)
                .orElseThrow();
        postCloseCycleService.failPhase(
                latePhaseClaim,
                new IllegalStateException("first failure in late phase"),
                NOW.plusSeconds(1)
        );

        PostCloseCycle failed = postCloseCycleService.findById(cycle.id()).orElseThrow();

        assertThat(List.<Object>of(
                latePhaseClaim.attemptNo(),
                failed.nextRetryAt(),
                postCloseCycleService.tryClaim(
                        cycle.id(),
                        PostClosePhase.CLOSE_REQUESTED,
                        NOW.plusSeconds(30)
                ).isPresent()
        )).containsExactly(9, NOW.plusSeconds(31), false);
    }

    @Test
    void findOldestIncompleteFullMarketCycle_ignoresCompletedAndSymbolCycles() {
        PostCloseCycle completed = postCloseCycleService.ensureFullMarketCycle(LocalDate.of(2026, 7, 12), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set phase = 'COMPLETED', status = 'COMPLETED' where id = ?",
                completed.id()
        );
        postCloseCycleService.ensureSymbolCycle(LocalDate.of(2026, 7, 11), "demo001", NOW);
        PostCloseCycle oldestIncomplete = postCloseCycleService.ensureFullMarketCycle(LocalDate.of(2026, 7, 13), NOW);
        postCloseCycleService.ensureFullMarketCycle(LocalDate.of(2026, 7, 14), NOW);

        assertThat(postCloseCycleService.findOldestIncompleteFullMarketCycle())
                .contains(oldestIncomplete);
    }

    @Test
    void ensureSkippedFullMarketCycle_retry_reusesAuditableCompletedCycle() {
        PostCloseCycle first = postCloseCycleService.ensureSkippedFullMarketCycle(
                LocalDate.of(2026, 7, 16),
                "simulation clock passed",
                NOW
        );
        PostCloseCycle retried = postCloseCycleService.ensureSkippedFullMarketCycle(
                LocalDate.of(2026, 7, 16),
                "simulation clock passed",
                NOW.plusSeconds(1)
        );

        assertThat(List.<Object>of(
                first.id(),
                retried.id(),
                first.cycleKind(),
                first.skipReason(),
                first.phase(),
                first.status(),
                cycleCount()
        )).containsExactly(
                first.id(),
                first.id(),
                PostCloseCycleKind.SKIPPED,
                "simulation clock passed",
                PostClosePhase.COMPLETED,
                PostCloseCycleStatus.COMPLETED,
                1L
        );
    }

    @Test
    void prepareSkippedCycleForNextOpen_rearmsOnlyPreOpenSuffixAndIsIdempotent() {
        LocalDate skippedDate = LocalDate.of(2026, 7, 16);
        PostCloseCycle skipped = postCloseCycleService.ensureSkippedFullMarketCycle(
                skippedDate,
                "simulation clock passed",
                NOW
        );

        PostCloseCycle prepared = postCloseCycleService.prepareSkippedCycleForNextOpen(
                skippedDate,
                NOW.plusSeconds(1)
        );
        PostCloseCycle retried = postCloseCycleService.prepareSkippedCycleForNextOpen(
                skippedDate,
                NOW.plusSeconds(2)
        );

        assertThat(List.<Object>of(
                prepared.id(),
                retried.id(),
                prepared.cycleKind(),
                prepared.phase(),
                prepared.status(),
                prepared.phaseRevision(),
                queryLong("select count(*) from stock_post_close_cycle where id = "
                        + prepared.id() + " and completed_at is null") == 1L,
                skipped.id() == prepared.id()
        )).containsExactly(
                skipped.id(),
                skipped.id(),
                PostCloseCycleKind.SKIPPED,
                PostClosePhase.REPORTS_AGGREGATED,
                PostCloseCycleStatus.PENDING,
                2,
                true,
                true
        );
    }

    @Test
    void ensureSkippedFullMarketCycle_retryAfterPreOpenRearm_reusesCycle() {
        LocalDate skippedDate = LocalDate.of(2026, 7, 16);
        PostCloseCycle skipped = postCloseCycleService.ensureSkippedFullMarketCycle(
                skippedDate,
                "simulation clock passed",
                NOW
        );
        postCloseCycleService.prepareSkippedCycleForNextOpen(skippedDate, NOW.plusSeconds(1));

        PostCloseCycle retried = postCloseCycleService.ensureSkippedFullMarketCycle(
                skippedDate,
                "simulation clock passed",
                NOW.plusSeconds(2)
        );

        assertThat(List.<Object>of(retried.id(), retried.phase(), retried.status()))
                .containsExactly(
                        skipped.id(),
                        PostClosePhase.REPORTS_AGGREGATED,
                        PostCloseCycleStatus.PENDING
                );
    }

    @Test
    void isReadyToOpen_failedReadyPhase_failsClosed() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set phase = 'READY_TO_OPEN', status = 'FAILED' where id = ?",
                cycle.id()
        );

        assertThat(postCloseCycleService.isReadyToOpen(NOW.toLocalDate())).isFalse();
    }

    @Test
    void isReadyToOpen_pendingReadyPhase_returnsTrue() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set phase = 'READY_TO_OPEN', status = 'PENDING' where id = ?",
                cycle.id()
        );

        assertThat(postCloseCycleService.isReadyToOpen(NOW.toLocalDate())).isTrue();
    }

    @Test
    void isRuntimeCompatible_unknownAndDifferentBuildsWithSameSchema_returnsTrue() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        String schemaVersion = queryString(
                "select schema_version from stock_post_close_cycle where id = " + cycle.id()
        );
        jdbcTemplate.update(
                "update stock_post_close_cycle set build_version = 'unknown' where id = ?",
                cycle.id()
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_phase_attempt(
                    cycle_id, phase, attempt_no, owner_id, status,
                    started_at, completed_at, build_version, schema_version,
                    eod_contract_version, created_at, updated_at
                ) values (?, 'CLOSE_REQUESTED', 1, 'previous-node', 'COMPLETED',
                          ?, ?, 'unknown', ?, 'EOD_V1', ?, ?),
                         (?, 'LEDGER_FROZEN', 2, 'current-node', 'COMPLETED',
                          ?, ?, 'current-build', ?, 'EOD_V1', ?, ?)
                """,
                cycle.id(),
                NOW,
                NOW.plusSeconds(1),
                schemaVersion,
                NOW,
                NOW.plusSeconds(1),
                cycle.id(),
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                schemaVersion,
                NOW.plusSeconds(2),
                NOW.plusSeconds(3)
        );

        assertThat(postCloseCycleService.isRuntimeCompatible(cycle.id())).isTrue();
    }

    @Test
    void isRuntimeCompatible_cycleContinuedAcrossSchemaVersionsWithSameContract_returnsTrue() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set schema_version = '2026-07-22-eod-v3' where id = ?",
                cycle.id()
        );
        jdbcTemplate.update(
                """
                insert into stock_post_close_phase_attempt(
                    cycle_id, phase, attempt_no, owner_id, status,
                    started_at, completed_at, build_version, schema_version,
                    eod_contract_version, created_at, updated_at
                ) values (?, 'CLOSE_REQUESTED', 1, 'previous-node', 'COMPLETED',
                          ?, ?, 'previous-build', '2026-07-22-eod-v3', 'EOD_V1', ?, ?),
                         (?, 'REPORTS_AGGREGATED', 2, 'current-node', 'COMPLETED',
                          ?, ?, 'current-build', '2026-07-23-auto-profile-v2-direct', 'EOD_V1', ?, ?)
                """,
                cycle.id(),
                NOW,
                NOW.plusSeconds(1),
                NOW,
                NOW.plusSeconds(1),
                cycle.id(),
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                NOW.plusSeconds(2),
                NOW.plusSeconds(3)
        );

        assertThat(postCloseCycleService.isRuntimeCompatible(cycle.id())).isTrue();
    }

    @Test
    void isRuntimeCompatible_completedAttemptWithDifferentContract_returnsFalse() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                """
                insert into stock_post_close_phase_attempt(
                    cycle_id, phase, attempt_no, owner_id, status,
                    started_at, completed_at, build_version, schema_version,
                    eod_contract_version, created_at, updated_at
                ) values (?, 'CLOSE_REQUESTED', 1, 'previous-node', 'COMPLETED',
                          ?, ?, 'previous-build', 'previous-schema', 'EOD_V0', ?, ?)
                """,
                cycle.id(),
                NOW,
                NOW.plusSeconds(1),
                NOW,
                NOW.plusSeconds(1)
        );

        assertThat(postCloseCycleService.isRuntimeCompatible(cycle.id())).isFalse();
    }

    @Test
    void isRuntimeCompatible_cycleWithDifferentSchemaAndSameContract_returnsTrue() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set schema_version = 'previous-schema' where id = ?",
                cycle.id()
        );

        assertThat(postCloseCycleService.isRuntimeCompatible(cycle.id())).isTrue();
    }

    @Test
    void isRuntimeCompatible_cycleWithDifferentContract_returnsFalse() {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(NOW.toLocalDate(), NOW);
        jdbcTemplate.update(
                "update stock_post_close_cycle set eod_contract_version = 'EOD_V0' where id = ?",
                cycle.id()
        );

        assertThat(postCloseCycleService.isRuntimeCompatible(cycle.id())).isFalse();
    }

    private long cycleCount() {
        return queryLong("select count(*) from stock_post_close_cycle");
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
