package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.queue.AutoMarketReadyProfileQueue;
import stock.batch.service.automarket.v3.AutoParticipantActivityState;
import stock.batch.service.automarket.v3.AutoParticipantBehaviorKernel;
import stock.batch.service.automarket.v3.AutoParticipantV3Policy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

@Component
class AutoParticipantOrderScheduleService {

    private static final String BEHAVIOR_MODEL_VERSION = "V3";
    private static final int MAX_GUARD_INTERVAL_SECONDS = 3_600;
    private static final int MAX_LEASE_SECONDS = 3_600;
    private static final int MAX_DUE_LIMIT = 500;
    private static final int QUERY_CHUNK_SIZE = 500;
    private static final int WRITE_CHUNK_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysql;
    private final AutoParticipantBehaviorKernel behaviorKernel = new AutoParticipantBehaviorKernel();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String leaseOwner = "stock-batch-" + UUID.randomUUID();

    @Value("${stock.batch.auto-market.generation-guard-interval-seconds:300}")
    private int guardIntervalSeconds = 300;

    @Value("${stock.batch.auto-market.generation-lease-seconds:120}")
    private int leaseSeconds = 120;

    @Value("${stock.batch.auto-market.generation-due-limit-per-symbol:100}")
    private int dueLimitPerSymbol = 100;

    @Value("${stock.market-session.open-time:06:00}")
    private LocalTime marketOpenTime = LocalTime.of(6, 0);

    @Value("${stock.market-session.close-time:18:00}")
    private LocalTime marketCloseTime = LocalTime.of(18, 0);

    AutoParticipantOrderScheduleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    @PostConstruct
    void validateVolumeConfiguration() {
        validateRange(
                "generation-guard-interval-seconds",
                guardIntervalSeconds,
                30,
                MAX_GUARD_INTERVAL_SECONDS
        );
        validateRange("generation-lease-seconds", leaseSeconds, 1, MAX_LEASE_SECONDS);
        validateRange("generation-due-limit-per-symbol", dueLimitPerSymbol, 1, MAX_DUE_LIMIT);
        if (!marketOpenTime.isBefore(marketCloseTime)) {
            throw new IllegalStateException("stock.market-session.open-time must be before close-time");
        }
    }

    int ensureSchedules(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (strategies == null || strategies.isEmpty()) {
            return 0;
        }
        ActivePolicy activePolicy = requireActivePolicy(now.toLocalDate());
        Map<Long, AutoParticipantStrategy> strategyByAccountId = strategies.stream()
                .filter(this::hasIdentity)
                .collect(Collectors.toMap(
                        AutoParticipantStrategy::accountId,
                        strategy -> strategy,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (strategyByAccountId.isEmpty()) {
            return 0;
        }
        LocalDate tradeDate = now.toLocalDate();
        Map<Long, DailyBehaviorState> dailyStates = ensureDailyBehaviorStates(
                strategyByAccountId,
                tradeDate,
                activePolicy,
                now
        );
        Map<Long, ScheduleSnapshot> existingSchedules = findSchedules(strategyByAccountId.keySet().stream().toList());
        List<ScheduleDefinition> missing = new ArrayList<>();
        List<ScheduleDefinition> changedDate = new ArrayList<>();
        for (AutoParticipantStrategy strategy : strategyByAccountId.values()) {
            DailyBehaviorState state = dailyStates.get(strategy.accountId());
            if (state == null) {
                throw new IllegalStateException(
                        "V3 daily behavior state was not created for account " + strategy.accountId()
                );
            }
            ScheduleDefinition definition = scheduleDefinition(strategy, state, activePolicy, now);
            ScheduleSnapshot existing = existingSchedules.get(strategy.accountId());
            if (existing == null) {
                missing.add(definition);
            } else if (!existing.tradeDate().equals(tradeDate)
                    || !existing.profileType().equals(strategy.profileType().name())
                    || !BEHAVIOR_MODEL_VERSION.equals(existing.behaviorModelVersion())) {
                changedDate.add(definition);
            }
        }
        int inserted = insertSchedules(missing, now);
        updateSchedulesForNewTradeDate(changedDate, now);
        return inserted;
    }

    List<AutoParticipantStrategy> claimDueStrategies(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        return claimDueStrategies(strategies, profilePolicies, now, true);
    }

    List<AutoParticipantStrategy> claimDueStrategies(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now,
            boolean seedMissingSchedules
    ) {
        if (strategies == null || strategies.isEmpty()) {
            return List.of();
        }
        if (seedMissingSchedules) {
            ensureSchedules(strategies, profilePolicies, now);
        }
        Map<Long, AutoParticipantStrategy> strategyByAccountId = strategies.stream()
                .filter(this::hasIdentity)
                .collect(Collectors.toMap(
                        AutoParticipantStrategy::accountId,
                        strategy -> strategy,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<DueAttention> due = findDueAttention(
                strategyByAccountId.keySet().stream().toList(),
                now
        );
        if (due.isEmpty()) {
            return List.of();
        }
        refreshObservedActivity(due, now);
        due = findDueAttention(due.stream().map(DueAttention::accountId).toList(), now);
        List<Long> dueAccountIds = due.stream().map(DueAttention::accountId).toList();
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        int updatedRows = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        update stock_auto_participant_order_schedule
                           set lease_until = :leaseUntil,
                               lease_owner = :leaseOwner,
                               updated_at = :now
                         where account_id in (:accountIds)
                           and next_attention_at is not null
                           and next_attention_at <= :now
                           and (lease_until is null or lease_until <= :now)
                        """
                )
                .param("leaseUntil", leaseUntil)
                .param("leaseOwner", leaseOwner)
                .param("now", now)
                .param("accountIds", dueAccountIds)
                .update();
        if (updatedRows <= 0) {
            return List.of();
        }
        Set<Long> claimedAccountIds = updatedRows == dueAccountIds.size()
                ? Set.copyOf(dueAccountIds)
                : findClaimedAccountIds(dueAccountIds, now);
        return due.stream()
                .filter(row -> claimedAccountIds.contains(row.accountId()))
                .map(row -> withDecisionContext(
                        strategyByAccountId.get(row.accountId()),
                        row
                ))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    List<AutoMarketReadyProfileQueue.ReadyProfile> findDueProfileSchedules(LocalDateTime now, int limit) {
        return profileSchedules(now, limit, true, null);
    }

    List<AutoMarketReadyProfileQueue.ReadyProfile> findNextProfileSchedules(LocalDateTime now, int limit) {
        return profileSchedules(now, limit, false, null);
    }

    List<AutoMarketReadyProfileQueue.ReadyProfile> findNextProfileSchedules(
            List<AutoParticipantProfileType> profileTypes,
            LocalDateTime fallbackReadyAt
    ) {
        if (profileTypes == null || profileTypes.isEmpty()) {
            return List.of();
        }
        Set<String> names = profileTypes.stream()
                .map(AutoParticipantProfileType::name)
                .collect(Collectors.toSet());
        List<AutoMarketReadyProfileQueue.ReadyProfile> loaded = profileSchedules(
                fallbackReadyAt,
                Math.max(1, names.size()),
                false,
                names
        );
        Map<AutoParticipantProfileType, LocalDateTime> readyByProfile = loaded.stream()
                .collect(Collectors.toMap(
                        AutoMarketReadyProfileQueue.ReadyProfile::profileType,
                        AutoMarketReadyProfileQueue.ReadyProfile::readyAt,
                        (left, right) -> left
                ));
        return profileTypes.stream()
                .distinct()
                .filter(readyByProfile::containsKey)
                .map(profile -> new AutoMarketReadyProfileQueue.ReadyProfile(
                        profile,
                        readyByProfile.get(profile)
                ))
                .toList();
    }

    int completeStrategies(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (strategies == null || strategies.isEmpty()) {
            return 0;
        }
        ActivePolicy activePolicy = requireActivePolicy(now.toLocalDate());
        List<Completion> completions = new ArrayList<>();
        for (AutoParticipantStrategy strategy : uniqueStrategies(strategies)) {
            DailyBehaviorState state = findDailyBehaviorState(strategy.accountId(), now.toLocalDate());
            if (state == null) {
                throw new IllegalStateException(
                        "V3 completion requires daily behavior state for account " + strategy.accountId()
                );
            }
            long nextEventSequence = state.eventSequence() + 1L;
            LocalDateTime nextAttention = activePolicy.runtimeEnabled()
                    ? behaviorKernel.nextAttentionAt(
                            now,
                            now.toLocalDate().atTime(marketOpenTime),
                            now.toLocalDate().atTime(marketCloseTime),
                            state.activityState(),
                            strategy.intensity(),
                            strategy.behaviorSeed(),
                            nextEventSequence,
                            activePolicy.policy()
                    )
                    : null;
            completions.add(new Completion(
                    strategy.accountId(),
                    now.toLocalDate(),
                    nextEventSequence,
                    nextAttention
            ));
        }
        int completed = 0;
        for (int start = 0; start < completions.size(); start += WRITE_CHUNK_SIZE) {
            int end = Math.min(completions.size(), start + WRITE_CHUNK_SIZE);
            List<Completion> chunk = completions.subList(start, end);
            completed += completeStateChunk(chunk, now);
            completeScheduleChunk(chunk, now);
        }
        return completed;
    }

    List<Long> findDueGuardAccountIds(LocalDateTime now, int limit) {
        List<Long> dueAccountIds = jdbcTemplate.query(
                """
                select account_id
                  from stock_auto_participant_order_schedule
                 where next_guard_at <= ?
                   and (lease_until is null or lease_until <= ?)
                 order by next_guard_at asc, account_id asc
                 limit ?
                """,
                (rs, rowNum) -> rs.getLong("account_id"),
                now,
                now,
                Math.clamp(limit, 1, MAX_DUE_LIMIT)
        );
        if (dueAccountIds.isEmpty()) {
            return List.of();
        }
        JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        update stock_auto_participant_order_schedule
                           set lease_until = :leaseUntil,
                               lease_owner = :leaseOwner,
                               updated_at = :now
                         where account_id in (:accountIds)
                           and next_guard_at <= :now
                           and (lease_until is null or lease_until <= :now)
                        """
                )
                .param("leaseUntil", now.plusSeconds(leaseSeconds))
                .param("leaseOwner", leaseOwner)
                .param("now", now)
                .param("accountIds", dueAccountIds)
                .update();
        return findClaimedAccountIds(dueAccountIds, now).stream().sorted().toList();
    }

    int completeGuards(List<Long> accountIds, LocalDateTime now) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        long secondsToClose = Math.max(
                0L,
                java.time.Duration.between(now, now.toLocalDate().atTime(marketCloseTime)).toSeconds()
        );
        int effectiveGuardInterval = secondsToClose <= 900
                ? 15
                : secondsToClose <= 3_600 ? 60 : guardIntervalSeconds;
        LocalDateTime nextGuardAt = now.plusSeconds(effectiveGuardInterval);
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        update stock_auto_participant_order_schedule
                           set next_guard_at = :nextGuardAt,
                               next_run_at = case
                                   when next_attention_at is null then :nextGuardAt
                                   when next_attention_at < :nextGuardAt then next_attention_at
                                   else :nextGuardAt
                               end,
                               lease_until = null,
                               lease_owner = null,
                               updated_at = :now
                         where account_id in (:accountIds)
                           and lease_owner = :leaseOwner
                        """
                )
                .param("nextGuardAt", nextGuardAt)
                .param("now", now)
                .param("leaseOwner", leaseOwner)
                .param("accountIds", accountIds.stream().distinct().toList())
                .update();
    }

    private Map<Long, DailyBehaviorState> ensureDailyBehaviorStates(
            Map<Long, AutoParticipantStrategy> strategies,
            LocalDate tradeDate,
            ActivePolicy activePolicy,
            LocalDateTime now
    ) {
        Map<Long, DailyBehaviorState> current = findDailyBehaviorStates(
                strategies.keySet().stream().toList(),
                tradeDate
        );
        List<DailyBehaviorInsert> inserts = new ArrayList<>();
        for (AutoParticipantStrategy strategy : strategies.values()) {
            if (current.containsKey(strategy.accountId())) {
                continue;
            }
            AutoParticipantActivityState previousState = findPreviousActivityState(
                    strategy.accountId(),
                    tradeDate
            );
            AutoParticipantActivityState activityState = behaviorKernel.sampleDailyState(
                    previousState,
                    strategy.intensity(),
                    strategy.behaviorSeed(),
                    tradeDate,
                    activePolicy.policy()
            );
            long dailySeed = strategy.behaviorSeed()
                    ^ tradeDate.toEpochDay()
                    ^ Long.rotateLeft(activePolicy.policy().policyVersion(), 17);
            String activitySession = switch (Math.floorMod(Long.hashCode(dailySeed), 4)) {
                case 0 -> "OPENING";
                case 1 -> "MIDDAY";
                case 2 -> "CLOSING";
                default -> "RANDOM";
            };
            inserts.add(new DailyBehaviorInsert(
                    tradeDate,
                    strategy,
                    activePolicy.policy().policyVersion(),
                    activityState,
                    activitySession,
                    dailySeed
            ));
        }
        insertDailyBehaviorStates(inserts, now);
        return findDailyBehaviorStates(strategies.keySet().stream().toList(), tradeDate);
    }

    private int insertDailyBehaviorStates(List<DailyBehaviorInsert> inserts, LocalDateTime now) {
        int inserted = 0;
        for (int start = 0; start < inserts.size(); start += WRITE_CHUNK_SIZE) {
            int end = Math.min(inserts.size(), start + WRITE_CHUNK_SIZE);
            List<DailyBehaviorInsert> chunk = inserts.subList(start, end);
            String values = java.util.stream.IntStream.range(0, chunk.size())
                    .mapToObj(index -> """
                            (?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0.000000, ?,
                             0, 0.00, 0, 0.00, 0, null, null, null, null, null,
                             0.00000000, 0, ?, ?)
                            """.strip())
                    .collect(Collectors.joining(", "));
            String sql = mysql
                    ? """
                      insert ignore into stock_auto_participant_daily_behavior_state(
                          simulation_trade_date, account_id, user_key, profile_type,
                          policy_version, participant_config_version, activity_state, activity_session,
                          daily_seed, event_sequence, fatigue_score, fatigue_updated_at,
                          submitted_order_count, submitted_notional, observed_execution_count,
                          observed_execution_notional, observed_cancel_count, last_attention_at,
                          last_decision_at, last_order_at, last_result_reason, last_hold_reason,
                          recovery_factor, optimistic_version, created_at, updated_at
                      ) values %s
                      """.formatted(values)
                    : """
                      merge into stock_auto_participant_daily_behavior_state(
                          simulation_trade_date, account_id, user_key, profile_type,
                          policy_version, participant_config_version, activity_state, activity_session,
                          daily_seed, event_sequence, fatigue_score, fatigue_updated_at,
                          submitted_order_count, submitted_notional, observed_execution_count,
                          observed_execution_notional, observed_cancel_count, last_attention_at,
                          last_decision_at, last_order_at, last_result_reason, last_hold_reason,
                          recovery_factor, optimistic_version, created_at, updated_at
                      ) key(simulation_trade_date, account_id) values %s
                      """.formatted(values);
            List<Object> parameters = new ArrayList<>(chunk.size() * 12);
            for (DailyBehaviorInsert insert : chunk) {
                parameters.add(Date.valueOf(insert.tradeDate()));
                parameters.add(insert.strategy().accountId());
                parameters.add(insert.strategy().userKey());
                parameters.add(insert.strategy().profileType().name());
                parameters.add(insert.policyVersion());
                parameters.add(insert.activityState().name());
                parameters.add(insert.activitySession());
                parameters.add(insert.dailySeed());
                parameters.add(now);
                parameters.add(now);
                parameters.add(now);
            }
            inserted += jdbcTemplate.update(sql, parameters.toArray());
        }
        return inserted;
    }

    private Map<Long, DailyBehaviorState> findDailyBehaviorStates(List<Long> accountIds, LocalDate tradeDate) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DailyBehaviorState> result = new LinkedHashMap<>();
        for (int start = 0; start < accountIds.size(); start += QUERY_CHUNK_SIZE) {
            int end = Math.min(accountIds.size(), start + QUERY_CHUNK_SIZE);
            JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                    .sql(
                            """
                            select account_id, activity_state, event_sequence, policy_version
                              from stock_auto_participant_daily_behavior_state
                             where simulation_trade_date = :tradeDate
                               and account_id in (:accountIds)
                            """
                    )
                    .param("tradeDate", tradeDate)
                    .param("accountIds", accountIds.subList(start, end))
                    .query((rs, rowNum) -> new DailyBehaviorState(
                            rs.getLong("account_id"),
                            AutoParticipantActivityState.valueOf(rs.getString("activity_state")),
                            rs.getLong("event_sequence"),
                            rs.getLong("policy_version")
                    ))
                    .list()
                    .forEach(state -> result.put(state.accountId(), state));
        }
        return Map.copyOf(result);
    }

    private DailyBehaviorState findDailyBehaviorState(long accountId, LocalDate tradeDate) {
        return findDailyBehaviorStates(List.of(accountId), tradeDate).get(accountId);
    }

    private AutoParticipantActivityState findPreviousActivityState(long accountId, LocalDate tradeDate) {
        List<String> values = jdbcTemplate.query(
                """
                select activity_state
                  from stock_auto_participant_daily_behavior_state
                 where account_id = ?
                   and simulation_trade_date < ?
                 order by simulation_trade_date desc
                 limit 1
                """,
                (rs, rowNum) -> rs.getString("activity_state"),
                accountId,
                tradeDate
        );
        return values.isEmpty()
                ? AutoParticipantActivityState.NORMAL
                : AutoParticipantActivityState.valueOf(values.getFirst());
    }

    private ScheduleDefinition scheduleDefinition(
            AutoParticipantStrategy strategy,
            DailyBehaviorState state,
            ActivePolicy activePolicy,
            LocalDateTime now
    ) {
        LocalDate tradeDate = now.toLocalDate();
        LocalDateTime open = tradeDate.atTime(marketOpenTime);
        LocalDateTime close = tradeDate.atTime(marketCloseTime);
        LocalDateTime attentionStart = now.isBefore(open) ? open : now;
        LocalDateTime nextAttention = activePolicy.runtimeEnabled()
                ? behaviorKernel.nextAttentionAt(
                        attentionStart,
                        open,
                        close,
                        state.activityState(),
                        strategy.intensity(),
                        strategy.behaviorSeed(),
                        state.eventSequence() + 1L,
                        activePolicy.policy()
                )
                : null;
        LocalDateTime nextGuard = (now.isBefore(open) ? open : now).plusSeconds(guardIntervalSeconds);
        LocalDateTime nextRun = minimum(nextAttention, nextGuard);
        return new ScheduleDefinition(
                strategy.accountId(),
                strategy.userKey(),
                strategy.profileType(),
                tradeDate,
                nextAttention,
                nextGuard,
                nextRun,
                priority(strategy, state.activityState())
        );
    }

    private int insertSchedules(List<ScheduleDefinition> definitions, LocalDateTime now) {
        int inserted = 0;
        for (int start = 0; start < definitions.size(); start += WRITE_CHUNK_SIZE) {
            int end = Math.min(definitions.size(), start + WRITE_CHUNK_SIZE);
            List<ScheduleDefinition> chunk = definitions.subList(start, end);
            String values = java.util.stream.IntStream.range(0, chunk.size())
                    .mapToObj(index -> "(?, ?, ?, 'V3', ?, ?, ?, ?, null, null, null, ?, ?, ?)")
                    .collect(Collectors.joining(", "));
            String sql = mysql
                    ? """
                      insert ignore into stock_auto_participant_order_schedule(
                          account_id, user_key, profile_type, behavior_model_version,
                          simulation_trade_date, next_attention_at, next_guard_at, next_run_at,
                          last_run_at, lease_until, lease_owner, priority, created_at, updated_at
                      ) values %s
                      """.formatted(values)
                    : """
                      merge into stock_auto_participant_order_schedule(
                          account_id, user_key, profile_type, behavior_model_version,
                          simulation_trade_date, next_attention_at, next_guard_at, next_run_at,
                          last_run_at, lease_until, lease_owner, priority, created_at, updated_at
                      ) key(account_id) values %s
                      """.formatted(values);
            List<Object> parameters = new ArrayList<>(chunk.size() * 10);
            for (ScheduleDefinition definition : chunk) {
                parameters.add(definition.accountId());
                parameters.add(definition.userKey());
                parameters.add(definition.profileType().name());
                parameters.add(Date.valueOf(definition.tradeDate()));
                parameters.add(timestamp(definition.nextAttentionAt()));
                parameters.add(Timestamp.valueOf(definition.nextGuardAt()));
                parameters.add(Timestamp.valueOf(definition.nextRunAt()));
                parameters.add(definition.priority());
                parameters.add(now);
                parameters.add(now);
            }
            inserted += jdbcTemplate.update(sql, parameters.toArray());
        }
        return inserted;
    }

    private void updateSchedulesForNewTradeDate(List<ScheduleDefinition> definitions, LocalDateTime now) {
        for (ScheduleDefinition definition : definitions) {
            jdbcTemplate.update(
                    """
                    update stock_auto_participant_order_schedule
                       set user_key = ?,
                           profile_type = ?,
                           behavior_model_version = 'V3',
                           simulation_trade_date = ?,
                           next_attention_at = ?,
                           next_guard_at = ?,
                           next_run_at = ?,
                           last_run_at = null,
                           lease_until = null,
                           lease_owner = null,
                           priority = ?,
                           updated_at = ?
                     where account_id = ?
                    """,
                    definition.userKey(),
                    definition.profileType().name(),
                    definition.tradeDate(),
                    timestamp(definition.nextAttentionAt()),
                    definition.nextGuardAt(),
                    definition.nextRunAt(),
                    definition.priority(),
                    now,
                    definition.accountId()
            );
        }
    }

    private Map<Long, ScheduleSnapshot> findSchedules(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ScheduleSnapshot> schedules = new LinkedHashMap<>();
        for (int start = 0; start < accountIds.size(); start += QUERY_CHUNK_SIZE) {
            int end = Math.min(accountIds.size(), start + QUERY_CHUNK_SIZE);
            JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                    .sql(
                            """
                            select account_id, profile_type, behavior_model_version, simulation_trade_date
                              from stock_auto_participant_order_schedule
                             where account_id in (:accountIds)
                            """
                    )
                    .param("accountIds", accountIds.subList(start, end))
                    .query((rs, rowNum) -> new ScheduleSnapshot(
                            rs.getLong("account_id"),
                            rs.getString("profile_type"),
                            rs.getString("behavior_model_version"),
                            rs.getDate("simulation_trade_date").toLocalDate()
                    ))
                    .list()
                    .forEach(schedule -> schedules.put(schedule.accountId(), schedule));
        }
        return Map.copyOf(schedules);
    }

    private List<DueAttention> findDueAttention(List<Long> accountIds, LocalDateTime now) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select s.account_id, s.next_attention_at, d.policy_version,
                               d.event_sequence, d.activity_state, d.fatigue_score, d.last_order_at,
                               policy.policy_json
                          from stock_auto_participant_order_schedule s
                          join stock_auto_participant_daily_behavior_state d
                            on d.account_id = s.account_id
                           and d.simulation_trade_date = s.simulation_trade_date
                          join stock_auto_participant_policy_revision policy
                            on policy.policy_version = d.policy_version
                         where s.account_id in (:accountIds)
                           and s.behavior_model_version = 'V3'
                           and s.next_attention_at is not null
                           and s.next_attention_at <= :now
                           and (s.lease_until is null or s.lease_until <= :now)
                         order by s.priority desc, s.next_attention_at asc, s.account_id asc
                         limit :limit
                        """
                )
                .param("accountIds", accountIds)
                .param("now", now)
                .param("limit", dueLimitPerSymbol)
                .query((rs, rowNum) -> new DueAttention(
                        rs.getLong("account_id"),
                        rs.getTimestamp("next_attention_at").toLocalDateTime(),
                        rs.getLong("policy_version"),
                        rs.getLong("event_sequence") + 1L,
                        AutoParticipantActivityState.valueOf(rs.getString("activity_state")),
                        rs.getDouble("fatigue_score"),
                        rs.getTimestamp("last_order_at") == null
                                ? null
                                : rs.getTimestamp("last_order_at").toLocalDateTime(),
                        AutoParticipantV3Policy.fromJson(
                                rs.getLong("policy_version"),
                                rs.getString("policy_json"),
                                objectMapper
                        )
                ))
                .list();
    }

    /**
     * Execution and cancellation paths only append authoritative cumulative totals.
     * The attention clock consumes their deltas here so the hot matching path never
     * contends on the behavior-state row.
     */
    private void refreshObservedActivity(List<DueAttention> due, LocalDateTime now) {
        if (due.isEmpty()) {
            return;
        }
        Map<Long, DueAttention> dueByAccount = due.stream().collect(Collectors.toMap(
                DueAttention::accountId,
                row -> row
        ));
        List<ActivityFeedback> feedback = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select d.account_id, d.fatigue_score, d.fatigue_updated_at,
                               d.observed_execution_count, d.observed_execution_notional,
                               d.observed_cancel_count, d.optimistic_version,
                               coalesce(execution.execution_count, 0) as execution_count,
                               coalesce(execution.gross_amount, 0) as execution_notional,
                               (
                                   select count(*)
                                     from stock_order order_row
                                    where order_row.account_id = d.account_id
                                      and order_row.auto_policy_version is not null
                                      and order_row.status = 'CANCELLED'
                                      and order_row.created_at >= :tradeOpen
                                      and order_row.created_at < :nextTradeOpen
                               ) as cancel_count,
                               greatest(
                                   1,
                                   account_row.cash_balance + coalesce((
                                       select sum(
                                           holding.quantity
                                           * coalesce(price.current_price, holding.average_price)
                                       )
                                         from stock_holding holding
                                         left join stock_price price on price.symbol = holding.symbol
                                        where holding.account_id = d.account_id
                                   ), 0)
                               ) as liquid_asset
                          from stock_auto_participant_daily_behavior_state d
                          join stock_account account_row on account_row.id = d.account_id
                          left join stock_execution_account_day_summary execution
                            on execution.simulation_trade_date = d.simulation_trade_date
                           and execution.account_id = d.account_id
                         where d.simulation_trade_date = :tradeDate
                           and d.account_id in (:accountIds)
                        """
                )
                .param("tradeDate", now.toLocalDate())
                .param("tradeOpen", now.toLocalDate().atStartOfDay())
                .param("nextTradeOpen", now.toLocalDate().plusDays(1).atStartOfDay())
                .param("accountIds", dueByAccount.keySet())
                .query((rs, rowNum) -> new ActivityFeedback(
                        rs.getLong("account_id"),
                        rs.getDouble("fatigue_score"),
                        rs.getTimestamp("fatigue_updated_at").toLocalDateTime(),
                        rs.getLong("observed_execution_count"),
                        rs.getBigDecimal("observed_execution_notional"),
                        rs.getLong("observed_cancel_count"),
                        rs.getLong("optimistic_version"),
                        rs.getLong("execution_count"),
                        rs.getBigDecimal("execution_notional"),
                        rs.getLong("cancel_count"),
                        rs.getBigDecimal("liquid_asset")
                ))
                .list();
        for (ActivityFeedback row : feedback) {
            DueAttention context = dueByAccount.get(row.accountId());
            long executionDelta = Math.max(
                    0L,
                    row.executionCount() - row.observedExecutionCount()
            );
            BigDecimal notionalDelta = row.executionNotional()
                    .subtract(row.observedExecutionNotional())
                    .max(BigDecimal.ZERO);
            long cancelDelta = Math.max(0L, row.cancelCount() - row.observedCancelCount());
            long elapsedSeconds = Math.max(
                    0L,
                    Duration.between(row.fatigueUpdatedAt(), now).toSeconds()
            );
            double executedNotionalRatio = notionalDelta.divide(
                    row.liquidAsset(),
                    12,
                    java.math.RoundingMode.HALF_UP
            ).doubleValue();
            double fatigue = behaviorKernel.fatigue(
                    row.fatigueScore(),
                    elapsedSeconds,
                    0L,
                    0.0,
                    executedNotionalRatio,
                    false,
                    context.policy()
            ) + executionDelta * 0.08 + cancelDelta * 0.05;
            jdbcTemplate.update(
                    """
                    update stock_auto_participant_daily_behavior_state
                       set fatigue_score = ?,
                           fatigue_updated_at = ?,
                           observed_execution_count = ?,
                           observed_execution_notional = ?,
                           observed_cancel_count = ?,
                           optimistic_version = optimistic_version + 1,
                           updated_at = ?
                     where simulation_trade_date = ?
                       and account_id = ?
                       and optimistic_version = ?
                    """,
                    fatigue,
                    now,
                    row.executionCount(),
                    row.executionNotional(),
                    row.cancelCount(),
                    now,
                    now.toLocalDate(),
                    row.accountId(),
                    row.optimisticVersion()
            );
        }
    }

    private Set<Long> findClaimedAccountIds(List<Long> accountIds, LocalDateTime now) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select account_id
                          from stock_auto_participant_order_schedule
                         where account_id in (:accountIds)
                           and lease_owner = :leaseOwner
                           and lease_until > :now
                        """
                )
                .param("accountIds", accountIds)
                .param("leaseOwner", leaseOwner)
                .param("now", now)
                .query(Long.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<AutoMarketReadyProfileQueue.ReadyProfile> profileSchedules(
            LocalDateTime now,
            int limit,
            boolean dueOnly,
            Set<String> profileTypes
    ) {
        String profileFilter = profileTypes == null ? "" : " and p.profile_type in (:profileTypes)";
        String dueFilter = dueOnly ? " and s.next_attention_at <= :now" : "";
        JdbcClient.StatementSpec statement = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select p.profile_type, min(s.next_attention_at) as ready_at
                          from stock_auto_participant_order_schedule s
                          join stock_auto_participant p
                            on p.user_key = s.user_key
                           and p.enabled = true
                           and p.withdrawn_at is null
                          join stock_account a
                            on a.id = s.account_id
                           and a.status = 'ACTIVE'
                         where s.behavior_model_version = 'V3'
                           and s.next_attention_at is not null
                           and (s.lease_until is null or s.lease_until <= :now)
                        %s
                        %s
                         group by p.profile_type
                         order by ready_at asc, max(s.priority) desc, p.profile_type asc
                         limit :limit
                        """.formatted(profileFilter, dueFilter)
                )
                .param("now", now)
                .param("limit", Math.max(1, limit));
        if (profileTypes != null) {
            statement = statement.param("profileTypes", profileTypes);
        }
        return statement.query((rs, rowNum) -> new AutoMarketReadyProfileQueue.ReadyProfile(
                AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                rs.getTimestamp("ready_at").toLocalDateTime()
        )).list();
    }

    private int completeStateChunk(List<Completion> completions, LocalDateTime now) {
        String eventCases = cases(completions.size());
        String placeholders = placeholders(completions.size());
        String sql = """
                update stock_auto_participant_daily_behavior_state
                   set event_sequence = case account_id %s else event_sequence end,
                       last_attention_at = ?,
                       last_decision_at = ?,
                       recovery_factor = 0.00000000,
                       optimistic_version = optimistic_version + 1,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and account_id in (%s)
                """.formatted(eventCases, placeholders);
        List<Object> parameters = new ArrayList<>(completions.size() * 3 + 5);
        completions.forEach(completion -> {
            parameters.add(completion.accountId());
            parameters.add(completion.eventSequence());
        });
        parameters.add(now);
        parameters.add(now);
        parameters.add(now);
        parameters.add(completions.getFirst().tradeDate());
        completions.forEach(completion -> parameters.add(completion.accountId()));
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    private int completeScheduleChunk(List<Completion> completions, LocalDateTime now) {
        String attentionCases = cases(completions.size());
        String nextRunCases = cases(completions.size());
        String placeholders = placeholders(completions.size());
        String sql = """
                update stock_auto_participant_order_schedule
                   set next_attention_at = case account_id %s else next_attention_at end,
                       next_run_at = case account_id %s else next_run_at end,
                       last_run_at = ?,
                       lease_until = null,
                       lease_owner = null,
                       updated_at = ?
                 where account_id in (%s)
                   and lease_owner = ?
                """.formatted(attentionCases, nextRunCases, placeholders);
        List<Object> parameters = new ArrayList<>(completions.size() * 5 + 3);
        completions.forEach(completion -> {
            parameters.add(completion.accountId());
            parameters.add(timestamp(completion.nextAttentionAt()));
        });
        completions.forEach(completion -> {
            parameters.add(completion.accountId());
            LocalDateTime nextGuard = findNextGuardAt(completion.accountId());
            parameters.add(minimum(completion.nextAttentionAt(), nextGuard));
        });
        parameters.add(now);
        parameters.add(now);
        completions.forEach(completion -> parameters.add(completion.accountId()));
        parameters.add(leaseOwner);
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    private LocalDateTime findNextGuardAt(long accountId) {
        return jdbcTemplate.queryForObject(
                "select next_guard_at from stock_auto_participant_order_schedule where account_id = ?",
                LocalDateTime.class,
                accountId
        );
    }

    private ActivePolicy requireActivePolicy(LocalDate tradeDate) {
        List<ActivePolicy> policies = jdbcTemplate.query(
                """
                select policy_version, runtime_enabled, policy_json
                  from stock_auto_participant_policy_revision
                 where status = 'ACTIVE'
                   and effective_trade_date <= ?
                 order by effective_trade_date desc, policy_version desc
                 limit 2
                """,
                (rs, rowNum) -> new ActivePolicy(
                        AutoParticipantV3Policy.fromJson(
                                rs.getLong("policy_version"),
                                rs.getString("policy_json"),
                                objectMapper
                        ),
                        rs.getBoolean("runtime_enabled")
                ),
                tradeDate
        );
        if (policies.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one effective ACTIVE V3 auto-participant policy is required: found=" + policies.size()
            );
        }
        return policies.getFirst();
    }

    private List<AutoParticipantStrategy> uniqueStrategies(List<AutoParticipantStrategy> strategies) {
        return strategies.stream()
                .filter(this::hasIdentity)
                .collect(Collectors.toMap(
                        AutoParticipantStrategy::accountId,
                        strategy -> strategy,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private AutoParticipantStrategy withDecisionContext(
            AutoParticipantStrategy strategy,
            DueAttention due
    ) {
        if (strategy == null) {
            return null;
        }
        return new AutoParticipantStrategy(
                strategy.userKey(),
                strategy.accountId(),
                strategy.intensity(),
                strategy.profileType(),
                strategy.recurringCashAmount(),
                strategy.recurringCashIntervalValue(),
                strategy.recurringCashIntervalUnit(),
                strategy.behaviorModelVersion(),
                strategy.behaviorSeed(),
                due.nextAttentionAt(),
                due.policyVersion(),
                due.eventSequence(),
                due.activityState(),
                due.fatigueScore(),
                due.lastOrderAt(),
                due.policy()
        );
    }

    private boolean hasIdentity(AutoParticipantStrategy strategy) {
        return strategy != null
                && strategy.accountId() > 0
                && strategy.userKey() != null
                && !strategy.userKey().isBlank();
    }

    private int priority(
            AutoParticipantStrategy strategy,
            AutoParticipantActivityState activityState
    ) {
        int statePriority = switch (activityState) {
            case OFFLINE -> 1;
            case LOW -> 20;
            case NORMAL -> 50;
            case HIGH -> 80;
        };
        return Math.clamp(statePriority + strategy.intensity() * 2, 1, 100);
    }

    private LocalDateTime minimum(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private String cases(int rowCount) {
        return java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(index -> "when ? then ?")
                .collect(Collectors.joining(" "));
    }

    private String placeholders(int rowCount) {
        return java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(index -> "?")
                .collect(Collectors.joining(", "));
    }

    private void validateRange(String propertyName, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(
                    "stock.batch.auto-market.%s must be between %d and %d: %d"
                            .formatted(propertyName, minimum, maximum, value)
            );
        }
    }

    private record ActivePolicy(AutoParticipantV3Policy policy, boolean runtimeEnabled) {
    }

    private record DailyBehaviorState(
            long accountId,
            AutoParticipantActivityState activityState,
            long eventSequence,
            long policyVersion
    ) {
    }

    private record DailyBehaviorInsert(
            LocalDate tradeDate,
            AutoParticipantStrategy strategy,
            long policyVersion,
            AutoParticipantActivityState activityState,
            String activitySession,
            long dailySeed
    ) {
    }

    private record ScheduleDefinition(
            long accountId,
            String userKey,
            AutoParticipantProfileType profileType,
            LocalDate tradeDate,
            LocalDateTime nextAttentionAt,
            LocalDateTime nextGuardAt,
            LocalDateTime nextRunAt,
            int priority
    ) {
    }

    private record ScheduleSnapshot(
            long accountId,
            String profileType,
            String behaviorModelVersion,
            LocalDate tradeDate
    ) {
    }

    private record DueAttention(
            long accountId,
            LocalDateTime nextAttentionAt,
            long policyVersion,
            long eventSequence,
            AutoParticipantActivityState activityState,
            double fatigueScore,
            LocalDateTime lastOrderAt,
            AutoParticipantV3Policy policy
    ) {
    }

    private record ActivityFeedback(
            long accountId,
            double fatigueScore,
            LocalDateTime fatigueUpdatedAt,
            long observedExecutionCount,
            BigDecimal observedExecutionNotional,
            long observedCancelCount,
            long optimisticVersion,
            long executionCount,
            BigDecimal executionNotional,
            long cancelCount,
            BigDecimal liquidAsset
    ) {
    }

    private record Completion(
            long accountId,
            LocalDate tradeDate,
            long eventSequence,
            LocalDateTime nextAttentionAt
    ) {
    }
}
