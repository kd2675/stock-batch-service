package stock.batch.service.marketclose.biz;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.common.config.StockRuntimeIdentity;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleKind;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;
import stock.batch.service.marketclose.model.PostCloseScopeType;

@Service
public class PostCloseCycleService {

    private static final String FULL_MARKET_SCOPE_KEY = "ALL";

    private final JdbcClient jdbcClient;
    private final String ownerId;
    private final long leaseSeconds;
    private final long retryBaseSeconds;
    private final long retryMaxSeconds;
    private final long deferredRetrySeconds;
    private final String buildVersion;
    private final String schemaVersion;

    public PostCloseCycleService(
            JdbcClient jdbcClient,
            BatchJobLockRegistry batchJobLockRegistry,
            @Value("${stock.batch.post-close.lease-seconds:180}") long leaseSeconds,
            @Value("${stock.batch.post-close.retry-base-seconds:30}") long retryBaseSeconds,
            @Value("${stock.batch.post-close.retry-max-seconds:900}") long retryMaxSeconds,
            @Value("${stock.batch.post-close.deferred-retry-seconds:60}") long deferredRetrySeconds,
            StockRuntimeIdentity runtimeIdentity
    ) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("Post-close cycle lease must be positive");
        }
        if (retryBaseSeconds <= 0 || retryMaxSeconds < retryBaseSeconds) {
            throw new IllegalArgumentException("Post-close retry backoff configuration is invalid");
        }
        if (deferredRetrySeconds <= 0 || deferredRetrySeconds > retryMaxSeconds) {
            throw new IllegalArgumentException("Post-close deferred retry configuration is invalid");
        }
        this.jdbcClient = jdbcClient;
        this.ownerId = batchJobLockRegistry.lockOwner();
        this.leaseSeconds = leaseSeconds;
        this.retryBaseSeconds = retryBaseSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
        this.deferredRetrySeconds = deferredRetrySeconds;
        this.buildVersion = normalizeVersion(runtimeIdentity.buildVersion());
        this.schemaVersion = normalizeVersion(runtimeIdentity.schemaVersion());
    }

    @Transactional
    public PostCloseCycle ensureFullMarketCycle(LocalDate businessDate, LocalDateTime now) {
        return ensureCycle(businessDate, PostCloseScopeType.FULL_MARKET, FULL_MARKET_SCOPE_KEY, now);
    }

    @Transactional
    public PostCloseCycle ensureSymbolCycle(LocalDate businessDate, String symbol, LocalDateTime now) {
        return ensureCycle(businessDate, PostCloseScopeType.SYMBOL, normalizeSymbol(symbol), now);
    }

    @Transactional
    public PostCloseCycle ensureSkippedFullMarketCycle(
            LocalDate businessDate,
            String skipReason,
            LocalDateTime now
    ) {
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
        String normalizedReason = truncate(skipReason, 500);
        if (normalizedReason == null || normalizedReason.isBlank()) {
            throw new IllegalArgumentException("skipReason is required");
        }
        try {
            jdbcClient.sql(
                            """
                            insert into stock_post_close_cycle(
                                business_date, scope_type, scope_key, cycle_kind, skip_reason,
                                phase, status, phase_revision, version, owner_id, lease_until,
                                close_run_id, settlement_eligible_at, attempt_count,
                                next_retry_at,
                                started_at, completed_at, last_error_code, last_error_message,
                                build_version, schema_version, created_at, updated_at
                            )
                            values (?, 'FULL_MARKET', 'ALL', 'SKIPPED', ?,
                                    'COMPLETED', 'COMPLETED', 1, 0, null, null,
                                    null, null, 0, null, ?, ?, null, null, ?, ?, ?, ?)
                            """
                    )
                    .param(businessDate)
                    .param(normalizedReason)
                    .param(now)
                    .param(now)
                    .param(buildVersion)
                    .param(schemaVersion)
                    .param(now)
                    .param(now)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // Retrying a committed recovery is expected after a coordinator crash.
        }
        PostCloseCycle cycle = findFullMarketCycle(businessDate).orElseThrow(
                () -> new IllegalStateException("Unable to create or load skipped business-date cycle")
        );
        if (cycle.cycleKind() != PostCloseCycleKind.SKIPPED) {
            throw new IllegalStateException(
                    "Business date already has a non-skipped close cycle: businessDate=" + businessDate
            );
        }
        return cycle;
    }

    /**
     * Reuses the last skipped date as the lightweight PRE_OPEN carrier for the next date.
     * Close, settlement, overnight cash, and report stages remain skipped because the date had
     * no trading ledger. Only the bounded security-transform, market-data, auto-market, and
     * readiness suffix is resumed. The conditional update makes crash recovery idempotent and
     * operates solely on the low-write cycle table.
     */
    @Transactional
    public PostCloseCycle prepareSkippedCycleForNextOpen(LocalDate businessDate, LocalDateTime now) {
        if (businessDate == null || now == null) {
            throw new IllegalArgumentException("Skipped-cycle PRE_OPEN preparation requires date and time");
        }
        jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set phase = 'REPORTS_AGGREGATED',
                               status = 'PENDING',
                               phase_revision = phase_revision + 1,
                               version = version + 1,
                               owner_id = null,
                               lease_until = null,
                               next_retry_at = null,
                               completed_at = null,
                               last_error_code = null,
                               last_error_message = null,
                               updated_at = ?
                         where business_date = ?
                           and scope_type = 'FULL_MARKET'
                           and scope_key = 'ALL'
                           and cycle_kind = 'SKIPPED'
                           and phase = 'COMPLETED'
                           and status = 'COMPLETED'
                           and phase_revision = 1
                        """
                )
                .param(now)
                .param(businessDate)
                .update();
        PostCloseCycle cycle = findFullMarketCycle(businessDate).orElseThrow(
                () -> new IllegalStateException("Skipped business-date cycle does not exist: " + businessDate)
        );
        boolean prepared = cycle.cycleKind() == PostCloseCycleKind.SKIPPED
                && cycle.phase().ordinal() >= PostClosePhase.REPORTS_AGGREGATED.ordinal()
                && cycle.phaseRevision() > 1;
        if (!prepared) {
            throw new IllegalStateException(
                    "Skipped business-date cycle cannot prepare the next open: businessDate=%s, phase=%s, status=%s"
                            .formatted(businessDate, cycle.phase(), cycle.status())
            );
        }
        return cycle;
    }

    @Transactional
    public Optional<PostClosePhaseClaim> tryClaim(
            long cycleId,
            PostClosePhase expectedPhase,
            LocalDateTime now
    ) {
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set status = 'RUNNING',
                               owner_id = ?,
                               lease_until = ?,
                               next_retry_at = null,
                               attempt_count = attempt_count + 1,
                               started_at = coalesce(started_at, ?),
                               last_error_code = null,
                               last_error_message = null,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and phase = ?
                           and (
                               status = 'PENDING'
                               or (status in ('DEFERRED', 'FAILED')
                                   and (next_retry_at is null or next_retry_at <= ?))
                               or (status = 'RUNNING' and lease_until <= ?)
                           )
                        """
                )
                .param(ownerId)
                .param(leaseUntil)
                .param(now)
                .param(now)
                .param(cycleId)
                .param(expectedPhase.name())
                .param(now)
                .param(now)
                .update();
        if (updated != 1) {
            return Optional.empty();
        }

        PostCloseCycle claimed = findById(cycleId).orElseThrow();
        jdbcClient.sql(
                        """
                        update stock_post_close_phase_attempt
                           set status = 'ABANDONED',
                               completed_at = ?,
                               error_code = 'LEASE_EXPIRED',
                               error_message = 'Cycle lease expired before phase completion',
                               updated_at = ?
                         where cycle_id = ?
                           and phase = ?
                           and status = 'RUNNING'
                        """
                )
                .param(now)
                .param(now)
                .param(cycleId)
                .param(expectedPhase.name())
                .update();
        jdbcClient.sql(
                        """
                        insert into stock_post_close_phase_attempt(
                            cycle_id, phase, attempt_no, batch_job_execution_id,
                            owner_id, status, started_at, completed_at,
                            error_code, error_message, build_version, schema_version,
                            created_at, updated_at
                        )
                        values (?, ?, ?, null, ?, 'RUNNING', ?, null, null, null, ?, ?, ?, ?)
                        """
                )
                .param(cycleId)
                .param(expectedPhase.name())
                .param(claimed.attemptCount())
                .param(ownerId)
                .param(now)
                .param(buildVersion)
                .param(schemaVersion)
                .param(now)
                .param(now)
                .update();
        return Optional.of(new PostClosePhaseClaim(
                cycleId,
                expectedPhase,
                claimed.attemptCount(),
                ownerId,
                leaseUntil
        ));
    }

    /**
     * Extends only a phase lease owned by this batch instance. A cycle can legitimately be
     * PENDING before its first Step claims it or already advanced by the final Step, so those
     * states are a no-op. A RUNNING cycle owned by another instance means ownership was lost and
     * must fail the enclosing Job heartbeat instead of allowing two phase writers to continue.
     */
    @Transactional
    public boolean renewOwnedRunningLease(long cycleId, LocalDateTime now) {
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set lease_until = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'RUNNING'
                           and owner_id = ?
                        """
                )
                .param(now.plusSeconds(leaseSeconds))
                .param(now)
                .param(cycleId)
                .param(ownerId)
                .update();
        if (updated == 1) {
            return true;
        }
        PostCloseCycle cycle = findById(cycleId).orElse(null);
        if (cycle != null && cycle.status() == PostCloseCycleStatus.RUNNING) {
            throw new IllegalStateException(
                    "Post-close cycle lease ownership was lost: cycleId=%d, owner=%s"
                            .formatted(cycleId, cycle.ownerId())
            );
        }
        return false;
    }

    public long leaseSeconds() {
        return leaseSeconds;
    }

    /**
     * Mirrors the conditional claim predicate without writing. Callers use this before acquiring
     * the heavy-job admission lease or creating Spring Batch metadata. The final correctness
     * decision remains the atomic {@link #tryClaim(long, PostClosePhase, LocalDateTime)} update.
     */
    public boolean isPhaseClaimEligible(PostCloseCycle cycle, LocalDateTime now) {
        if (cycle == null || now == null) {
            return false;
        }
        return switch (cycle.status()) {
            case PENDING -> true;
            case DEFERRED, FAILED -> cycle.nextRetryAt() == null || !now.isBefore(cycle.nextRetryAt());
            case RUNNING -> cycle.leaseUntil() != null && !now.isBefore(cycle.leaseUntil());
            case COMPLETED -> false;
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completePhase(
            PostClosePhaseClaim claim,
            PostClosePhase nextPhase,
            Long closeRunId,
            LocalDateTime settlementEligibleAt,
            LocalDateTime now
    ) {
        String nextStatus = nextPhase == PostClosePhase.COMPLETED ? "COMPLETED" : "PENDING";
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set phase = ?,
                               status = ?,
                               owner_id = null,
                               lease_until = null,
                               next_retry_at = null,
                               close_run_id = coalesce(?, close_run_id),
                               settlement_eligible_at = coalesce(?, settlement_eligible_at),
                               completed_at = case when ? = 'COMPLETED' then ? else completed_at end,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and phase = ?
                           and status = 'RUNNING'
                           and owner_id = ?
                           and attempt_count = ?
                        """
                )
                .param(nextPhase.name())
                .param(nextStatus)
                .param(closeRunId)
                .param(settlementEligibleAt)
                .param(nextPhase.name())
                .param(now)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.ownerId())
                .param(claim.attemptNo())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Post-close phase claim was lost before completion: cycleId=" + claim.cycleId());
        }
        completeAttempt(claim, now);
    }

    @Transactional
    public void completeClaim(
            PostClosePhaseClaim claim,
            PostClosePhase nextPhase,
            LocalDateTime now
    ) {
        completePhase(claim, nextPhase, null, null, now);
    }

    @Transactional
    public void deferPhase(PostClosePhaseClaim claim, String reason, LocalDateTime now) {
        String message = truncate(reason, 1000);
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set status = 'DEFERRED',
                               owner_id = null,
                               lease_until = null,
                               next_retry_at = ?,
                               last_error_code = 'DEFERRED',
                               last_error_message = ?,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and phase = ?
                           and status = 'RUNNING'
                           and owner_id = ?
                           and attempt_count = ?
                        """
                )
                .param(now.plusSeconds(deferredRetrySeconds))
                .param(message)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.ownerId())
                .param(claim.attemptNo())
                .update();
        if (updated != 1) {
            return;
        }
        jdbcClient.sql(
                        """
                        update stock_post_close_phase_attempt
                           set status = 'ABANDONED',
                               completed_at = ?,
                               error_code = 'DEFERRED',
                               error_message = ?,
                               updated_at = ?
                         where cycle_id = ?
                           and phase = ?
                           and attempt_no = ?
                           and owner_id = ?
                           and status = 'RUNNING'
                        """
                )
                .param(now)
                .param(message)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.attemptNo())
                .param(claim.ownerId())
                .update();
    }

    /**
     * Releases a phase that committed a bounded cohort but intentionally has more work left.
     * This is not counted as a no-progress failure, so a large overnight backlog advances at
     * the base retry interval instead of accumulating exponential delay. Only EOD control rows
     * are updated; regular-session order and execution tables are not read or locked here.
     */
    @Transactional
    public void continuePhaseAfterProgress(
            PostClosePhaseClaim claim,
            int processedCount,
            String reason,
            LocalDateTime now
    ) {
        if (processedCount <= 0) {
            throw new IllegalArgumentException("processedCount must be positive for phase continuation");
        }
        String message = truncate(
                "Bounded phase progress committed: processedCount=%d, reason=%s"
                        .formatted(processedCount, reason),
                1000
        );
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set status = 'DEFERRED',
                               owner_id = null,
                               lease_until = null,
                               next_retry_at = ?,
                               last_error_code = 'BOUNDED_PROGRESS',
                               last_error_message = ?,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and phase = ?
                           and status = 'RUNNING'
                           and owner_id = ?
                           and attempt_count = ?
                        """
                )
                .param(now.plusSeconds(retryBaseSeconds))
                .param(message)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.ownerId())
                .param(claim.attemptNo())
                .update();
        if (updated != 1) {
            return;
        }
        jdbcClient.sql(
                        """
                        update stock_post_close_phase_attempt
                           set status = 'ABANDONED',
                               completed_at = ?,
                               error_code = 'BOUNDED_PROGRESS',
                               error_message = ?,
                               updated_at = ?
                         where cycle_id = ?
                           and phase = ?
                           and attempt_no = ?
                           and owner_id = ?
                           and status = 'RUNNING'
                        """
                )
                .param(now)
                .param(message)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.attemptNo())
                .param(claim.ownerId())
                .update();
    }

    @Transactional
    public void linkBatchJobExecution(PostClosePhaseClaim claim, Long batchJobExecutionId, LocalDateTime now) {
        if (batchJobExecutionId == null) {
            return;
        }
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_phase_attempt
                           set batch_job_execution_id = ?,
                               updated_at = ?
                         where cycle_id = ?
                           and phase = ?
                           and attempt_no = ?
                           and owner_id = ?
                           and status = 'RUNNING'
                        """
                )
                .param(batchJobExecutionId)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.attemptNo())
                .param(claim.ownerId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Post-close phase attempt was lost before JobExecution link");
        }
    }

    /**
     * Links a coordinator-owned phase to the physical JobExecution created after the phase was
     * claimed. Market-close and settlement jobs claim inside their first Step and keep using the
     * explicit claim overload above; other overnight jobs are already RUNNING before launch.
     * The lookup/update touches only cycle and attempt control rows.
     */
    @Transactional
    public boolean linkOwnedBatchJobExecution(
            long cycleId,
            Long batchJobExecutionId,
            LocalDateTime now
    ) {
        if (batchJobExecutionId == null) {
            return false;
        }
        PostCloseCycle cycle = findById(cycleId).orElse(null);
        if (cycle == null || cycle.status() != PostCloseCycleStatus.RUNNING) {
            return false;
        }
        if (!ownerId.equals(cycle.ownerId())) {
            throw new IllegalStateException(
                    "Post-close cycle ownership was lost before JobExecution link: cycleId=%d, owner=%s"
                            .formatted(cycleId, cycle.ownerId())
            );
        }
        linkBatchJobExecution(
                new PostClosePhaseClaim(
                        cycle.id(),
                        cycle.phase(),
                        cycle.attemptCount(),
                        cycle.ownerId(),
                        cycle.leaseUntil()
                ),
                batchJobExecutionId,
                now
        );
        return true;
    }

    /**
     * Persists the physical close run before bounded freeze chunks begin. The link is part of the
     * same transaction that creates the run, so a crash can resume the logical cycle without
     * creating a second full-close run or losing the durable chunk checkpoints.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkCloseRun(PostClosePhaseClaim claim, long closeRunId, LocalDateTime now) {
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set close_run_id = ?,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and phase = ?
                           and status = 'RUNNING'
                           and owner_id = ?
                           and attempt_count = ?
                           and (close_run_id is null or close_run_id = ?)
                        """
                )
                .param(closeRunId)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.ownerId())
                .param(claim.attemptNo())
                .param(closeRunId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "Post-close phase claim was lost before close-run link: cycleId=" + claim.cycleId()
            );
        }
    }

    @Transactional
    public void failPhase(PostClosePhaseClaim claim, RuntimeException failure, LocalDateTime now) {
        String errorCode = failure.getClass().getSimpleName();
        String errorMessage = truncate(failure.getMessage(), 1000);
        int phaseFailureNumber = nextPhaseFailureNumber(claim);
        jdbcClient.sql(
                        """
                        update stock_post_close_cycle
                           set status = 'FAILED',
                               owner_id = null,
                               lease_until = null,
                               next_retry_at = ?,
                               last_error_code = ?,
                               last_error_message = ?,
                               version = version + 1,
                               updated_at = ?
                         where id = ?
                           and phase = ?
                           and status = 'RUNNING'
                           and owner_id = ?
                           and attempt_count = ?
                        """
                )
                .param(now.plusSeconds(calculateRetryDelaySeconds(phaseFailureNumber)))
                .param(errorCode)
                .param(errorMessage)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.ownerId())
                .param(claim.attemptNo())
                .update();
        jdbcClient.sql(
                        """
                        update stock_post_close_phase_attempt
                           set status = 'FAILED',
                               completed_at = ?,
                               error_code = ?,
                               error_message = ?,
                               updated_at = ?
                         where cycle_id = ?
                           and phase = ?
                           and attempt_no = ?
                           and owner_id = ?
                           and status = 'RUNNING'
                        """
                )
                .param(now)
                .param(errorCode)
                .param(errorMessage)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.attemptNo())
                .param(claim.ownerId())
                .update();
    }

    public Optional<PostCloseCycle> findById(long cycleId) {
        return jdbcClient.sql(
                        """
                        select id, business_date, scope_type, scope_key, cycle_kind, skip_reason, phase, status,
                               phase_revision, version, close_run_id, settlement_eligible_at,
                               attempt_count, owner_id, lease_until, next_retry_at
                          from stock_post_close_cycle
                         where id = ?
                        """
                )
                .param(cycleId)
                .query((rs, rowNum) -> mapCycle(rs))
                .optional();
    }

    public Optional<PostCloseCycle> findOldestIncompleteFullMarketCycle() {
        return jdbcClient.sql(
                        """
                        select id, business_date, scope_type, scope_key, cycle_kind, skip_reason, phase, status,
                               phase_revision, version, close_run_id, settlement_eligible_at,
                               attempt_count, owner_id, lease_until, next_retry_at
                          from stock_post_close_cycle
                         where scope_type = 'FULL_MARKET'
                           and scope_key = 'ALL'
                           and status <> 'COMPLETED'
                         order by business_date asc, id asc
                         limit 1
                        """
                )
                .query((rs, rowNum) -> mapCycle(rs))
                .optional();
    }

    public Optional<PostCloseCycle> findOldestUnsettledFullMarketCycle() {
        return jdbcClient.sql(
                        """
                        select id, business_date, scope_type, scope_key, cycle_kind, skip_reason, phase, status,
                               phase_revision, version, close_run_id, settlement_eligible_at,
                               attempt_count, owner_id, lease_until, next_retry_at
                          from stock_post_close_cycle
                         where scope_type = 'FULL_MARKET'
                           and scope_key = 'ALL'
                           and phase in (
                               'CLOSE_REQUESTED', 'ORDER_ENTRY_CLOSED',
                               'EXECUTION_DRAINED', 'LEDGER_FROZEN'
                           )
                         order by business_date asc, id asc
                         limit 1
                        """
                )
                .query((rs, rowNum) -> mapCycle(rs))
                .optional();
    }

    public Optional<PostCloseCycle> findFullMarketCycle(LocalDate businessDate) {
        if (businessDate == null) {
            return Optional.empty();
        }
        return findByScope(
                businessDate,
                PostCloseScopeType.FULL_MARKET,
                FULL_MARKET_SCOPE_KEY
        );
    }

    public boolean isReadyToOpen(LocalDate businessDate) {
        return findFullMarketCycle(businessDate)
                .filter(cycle -> (cycle.phase() == PostClosePhase.READY_TO_OPEN
                        && cycle.status() == PostCloseCycleStatus.PENDING)
                        || (cycle.phase() == PostClosePhase.COMPLETED
                        && cycle.status() == PostCloseCycleStatus.COMPLETED))
                .isPresent();
    }

    public boolean isSkippedCompleted(LocalDate businessDate) {
        return findFullMarketCycle(businessDate)
                .filter(cycle -> cycle.cycleKind() == PostCloseCycleKind.SKIPPED
                        && cycle.phase() == PostClosePhase.COMPLETED
                        && cycle.status() == PostCloseCycleStatus.COMPLETED)
                .isPresent();
    }

    public boolean isCompleted(LocalDate businessDate) {
        return findFullMarketCycle(businessDate)
                .filter(cycle -> cycle.phase() == PostClosePhase.COMPLETED
                        && cycle.status() == PostCloseCycleStatus.COMPLETED)
                .isPresent();
    }

    /**
     * A restart or rolling deployment may continue an existing cycle with a different build SHA.
     * The cycle keeps its creator build for audit, while every phase attempt records the build that
     * actually executed it. Readiness therefore gates on the durable schema contract, not exact
     * build equality or whether a local IDE build could resolve a SHA. This bounded control-table
     * lookup never reaches trading ledgers.
     */
    public boolean isRuntimeCompatible(long cycleId) {
        Boolean compatible = jdbcClient.sql(
                        """
                        select exists (
                            select 1
                             from stock_post_close_cycle cycle
                             where cycle.id = ?
                               and cycle.schema_version = ?
                               and not exists (
                                   select 1
                                     from stock_post_close_phase_attempt attempt
                                    where attempt.cycle_id = cycle.id
                                      and attempt.status = 'COMPLETED'
                                      and (
                                          attempt.schema_version is null
                                          or attempt.schema_version <> ?
                                      )
                               )
                        )
                        """
                )
                .param(cycleId)
                .param(schemaVersion)
                .param(schemaVersion)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(compatible);
    }

    private PostCloseCycle ensureCycle(
            LocalDate businessDate,
            PostCloseScopeType scopeType,
            String scopeKey,
            LocalDateTime now
    ) {
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
        try {
            jdbcClient.sql(
                            """
                            insert into stock_post_close_cycle(
                                business_date, scope_type, scope_key, cycle_kind, skip_reason, phase, status,
                                phase_revision, version, owner_id, lease_until,
                                close_run_id, settlement_eligible_at, attempt_count,
                                next_retry_at,
                                started_at, completed_at, last_error_code, last_error_message,
                                build_version, schema_version, created_at, updated_at
                            )
                            values (?, ?, ?, 'TRADING', null, 'CLOSE_REQUESTED', 'PENDING', 1, 0, null, null,
                                    null, null, 0, null, null, null, null, null, ?, ?, ?, ?)
                            """
                    )
                    .param(businessDate)
                    .param(scopeType.name())
                    .param(scopeKey)
                    .param(buildVersion)
                    .param(schemaVersion)
                    .param(now)
                    .param(now)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // The logical cycle already exists; reuse it instead of creating another close run.
        }
        return findByScope(businessDate, scopeType, scopeKey).orElseThrow(
                () -> new IllegalStateException("Unable to create or load post-close cycle")
        );
    }

    private Optional<PostCloseCycle> findByScope(
            LocalDate businessDate,
            PostCloseScopeType scopeType,
            String scopeKey
    ) {
        return jdbcClient.sql(
                        """
                        select id, business_date, scope_type, scope_key, cycle_kind, skip_reason, phase, status,
                               phase_revision, version, close_run_id, settlement_eligible_at,
                               attempt_count, owner_id, lease_until, next_retry_at
                          from stock_post_close_cycle
                         where business_date = ?
                           and scope_type = ?
                           and scope_key = ?
                        """
                )
                .param(businessDate)
                .param(scopeType.name())
                .param(scopeKey)
                .query((rs, rowNum) -> mapCycle(rs))
                .optional();
    }

    private PostCloseCycle mapCycle(ResultSet rs) throws SQLException {
        return new PostCloseCycle(
                rs.getLong("id"),
                rs.getObject("business_date", LocalDate.class),
                PostCloseScopeType.valueOf(rs.getString("scope_type")),
                rs.getString("scope_key"),
                PostCloseCycleKind.valueOf(rs.getString("cycle_kind")),
                rs.getString("skip_reason"),
                PostClosePhase.valueOf(rs.getString("phase")),
                PostCloseCycleStatus.valueOf(rs.getString("status")),
                rs.getInt("phase_revision"),
                rs.getLong("version"),
                rs.getObject("close_run_id", Long.class),
                rs.getObject("settlement_eligible_at", LocalDateTime.class),
                rs.getInt("attempt_count"),
                rs.getString("owner_id"),
                rs.getObject("lease_until", LocalDateTime.class),
                rs.getObject("next_retry_at", LocalDateTime.class)
        );
    }

    private long calculateRetryDelaySeconds(int phaseFailureNumber) {
        int exponent = Math.max(0, Math.min(20, phaseFailureNumber - 1));
        long multiplier = 1L << exponent;
        if (retryBaseSeconds > retryMaxSeconds / multiplier) {
            return retryMaxSeconds;
        }
        return Math.min(retryMaxSeconds, retryBaseSeconds * multiplier);
    }

    private int nextPhaseFailureNumber(PostClosePhaseClaim claim) {
        Long previousFailureCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_post_close_phase_attempt
                         where cycle_id = ?
                           and phase = ?
                           and status = 'FAILED'
                        """
                )
                .param(claim.cycleId())
                .param(claim.phase().name())
                .query(Long.class)
                .single();
        long nextFailureNumber = (previousFailureCount == null ? 0L : previousFailureCount) + 1L;
        return (int) Math.min(Integer.MAX_VALUE, nextFailureNumber);
    }

    private void completeAttempt(PostClosePhaseClaim claim, LocalDateTime now) {
        int updated = jdbcClient.sql(
                        """
                        update stock_post_close_phase_attempt
                           set status = 'COMPLETED',
                               completed_at = ?,
                               updated_at = ?
                         where cycle_id = ?
                           and phase = ?
                           and attempt_no = ?
                           and owner_id = ?
                           and status = 'RUNNING'
                        """
                )
                .param(now)
                .param(now)
                .param(claim.cycleId())
                .param(claim.phase().name())
                .param(claim.attemptNo())
                .param(claim.ownerId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Post-close phase attempt was lost before completion");
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeVersion(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
