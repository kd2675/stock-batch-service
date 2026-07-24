package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
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
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

@Component
class AutoParticipantOrderScheduleService {

    private static final int MAX_BASE_INTERVAL_SECONDS = 3_600;
    private static final int MAX_GENERATION_INTERVAL_SECONDS = 86_400;
    private static final int MAX_SPREAD_SECONDS = 3_600;
    private static final int MAX_LEASE_SECONDS = 3_600;
    private static final int MAX_DUE_LIMIT = 500;
    private static final int COMPLETION_ROW_CHUNK_SIZE = 100;
    private static final int SCHEDULE_QUERY_ROW_CHUNK_SIZE = 500;
    private static final int SCHEDULE_WRITE_ROW_CHUNK_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysql;

    @Value("${stock.batch.auto-market.generation-base-interval-seconds:10}")
    private int baseIntervalSeconds;

    @Value("${stock.batch.auto-market.generation-min-interval-seconds:3}")
    private int minIntervalSeconds;

    @Value("${stock.batch.auto-market.generation-max-interval-seconds:300}")
    private int maxIntervalSeconds;

    @Value("${stock.batch.auto-market.generation-spread-seconds:10}")
    private int spreadSeconds;

    @Value("${stock.batch.auto-market.generation-lease-seconds:120}")
    private int leaseSeconds;

    @Value("${stock.batch.auto-market.generation-due-limit-per-symbol:100}")
    private int dueLimitPerSymbol;

    private final String leaseOwner = "stock-batch-" + UUID.randomUUID();

    AutoParticipantOrderScheduleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    @PostConstruct
    void validateVolumeConfiguration() {
        validateRange("generation-base-interval-seconds", baseIntervalSeconds, 1, MAX_BASE_INTERVAL_SECONDS);
        validateRange("generation-min-interval-seconds", minIntervalSeconds, 1, MAX_GENERATION_INTERVAL_SECONDS);
        validateRange("generation-max-interval-seconds", maxIntervalSeconds, 1, MAX_GENERATION_INTERVAL_SECONDS);
        if (maxIntervalSeconds < minIntervalSeconds) {
            throw new IllegalStateException(
                    "stock.batch.auto-market.generation-max-interval-seconds must be greater than or equal to generation-min-interval-seconds"
            );
        }
        validateRange("generation-spread-seconds", spreadSeconds, 0, MAX_SPREAD_SECONDS);
        validateRange("generation-lease-seconds", leaseSeconds, 1, MAX_LEASE_SECONDS);
        validateRange("generation-due-limit-per-symbol", dueLimitPerSymbol, 1, MAX_DUE_LIMIT);
    }

    int ensureSchedules(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        return ensureScheduleEntries(strategies, profilePolicies, now);
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
        if (strategies.isEmpty()) {
            return List.of();
        }
        Map<String, AutoParticipantStrategy> strategyByUserKey = strategyByUserKey(
                strategies.stream()
                        .filter(strategy -> isDecisionEnabled(
                                policyForExecution(strategy, policy(profilePolicies, strategy.profileType()))
                        ))
                        .toList()
        );
        if (strategyByUserKey.isEmpty()) {
            return List.of();
        }
        if (seedMissingSchedules) {
            ensureScheduleEntries(strategies, profilePolicies, now);
        }
        List<String> dueUserKeys = findDueUserKeys(strategyByUserKey.keySet().stream().toList(), now);
        if (dueUserKeys.isEmpty()) {
            return List.of();
        }
        LocalDateTime leaseUntil = now.plusSeconds(Math.max(1, leaseSeconds));
        int updatedRows = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        update stock_auto_participant_order_schedule
                           set lease_until = :leaseUntil,
                               lease_owner = :leaseOwner,
                               updated_at = :now
                         where user_key in (:userKeys)
                           and next_run_at <= :now
                           and (lease_until is null or lease_until <= :now)
                        """
                )
                .param("leaseUntil", leaseUntil)
                .param("leaseOwner", leaseOwner)
                .param("now", now)
                .param("userKeys", dueUserKeys)
                .update();
        if (updatedRows <= 0) {
            return List.of();
        }
        Set<String> claimedUserKeys = updatedRows == dueUserKeys.size()
                ? Set.copyOf(dueUserKeys)
                : findClaimedUserKeys(dueUserKeys, now);
        return dueUserKeys.stream()
                .filter(claimedUserKeys::contains)
                .map(strategyByUserKey::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    List<AutoMarketReadyProfileQueue.ReadyProfile> findDueProfileSchedules(LocalDateTime now, int limit) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select p.profile_type, min(s.next_run_at) as ready_at
                        from stock_auto_participant_order_schedule s
                        join stock_auto_participant p
                          on p.user_key = s.user_key
                         and p.enabled = true
                         and p.withdrawn_at is null
                        join stock_account a
                          on a.user_key = p.user_key
                         and a.status = 'ACTIVE'
                        where s.next_run_at <= :now
                          and (s.lease_until is null or s.lease_until <= :now)
                        group by p.profile_type
                        order by ready_at asc, max(s.priority) desc, p.profile_type asc
                        limit :limit
                        """
                )
                .param("now", now)
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> new AutoMarketReadyProfileQueue.ReadyProfile(
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getTimestamp("ready_at").toLocalDateTime()
                ))
                .list();
    }

    List<AutoMarketReadyProfileQueue.ReadyProfile> findNextProfileSchedules(LocalDateTime now, int limit) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select p.profile_type,
                               min(case
                                     when s.lease_until is not null
                                      and s.lease_until > :now
                                      and s.lease_until > s.next_run_at
                                     then s.lease_until
                                     else s.next_run_at
                                   end) as ready_at
                        from stock_auto_participant_order_schedule s
                        join stock_auto_participant p
                          on p.user_key = s.user_key
                         and p.enabled = true
                         and p.withdrawn_at is null
                        join stock_account a
                          on a.user_key = p.user_key
                         and a.status = 'ACTIVE'
                        group by p.profile_type
                        order by ready_at asc, max(s.priority) desc, p.profile_type asc
                        limit :limit
                        """
                )
                .param("now", now)
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> new AutoMarketReadyProfileQueue.ReadyProfile(
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getTimestamp("ready_at").toLocalDateTime()
                ))
                .list();
    }

    List<AutoMarketReadyProfileQueue.ReadyProfile> findNextProfileSchedules(
            List<AutoParticipantProfileType> profileTypes,
            LocalDateTime fallbackReadyAt
    ) {
        if (profileTypes.isEmpty()) {
            return List.of();
        }
        List<String> profileTypeNames = profileTypes.stream()
                .distinct()
                .map(AutoParticipantProfileType::name)
                .toList();
        Map<AutoParticipantProfileType, LocalDateTime> nextReadyAtByProfile = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select p.profile_type,
                               min(case
                                     when s.lease_until is not null
                                      and s.lease_until > :fallbackReadyAt
                                      and s.lease_until > s.next_run_at
                                     then s.lease_until
                                     else s.next_run_at
                                   end) as ready_at
                        from stock_auto_participant_order_schedule s
                        join stock_auto_participant p
                          on p.user_key = s.user_key
                         and p.enabled = true
                         and p.withdrawn_at is null
                        join stock_account a
                          on a.user_key = p.user_key
                         and a.status = 'ACTIVE'
                        where p.profile_type in (:profileTypes)
                        group by p.profile_type
                        """
                )
                .param("profileTypes", profileTypeNames)
                .param("fallbackReadyAt", fallbackReadyAt)
                .query((rs, rowNum) -> Map.entry(
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getTimestamp("ready_at").toLocalDateTime()
                ))
                .list()
                .stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
        List<AutoMarketReadyProfileQueue.ReadyProfile> profiles = new ArrayList<>();
        for (AutoParticipantProfileType profileType : profileTypes) {
            LocalDateTime nextReadyAt = nextReadyAtByProfile.get(profileType);
            if (nextReadyAt != null) {
                profiles.add(new AutoMarketReadyProfileQueue.ReadyProfile(profileType, nextReadyAt));
            }
        }
        return profiles;
    }

    int completeStrategies(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        int completed = deleteSchedules(strategies.stream()
                .filter(strategy -> !isDecisionEnabled(
                        policyForExecution(strategy, policy(profilePolicies, strategy.profileType()))
                ))
                .map(AutoParticipantStrategy::userKey)
                .filter(userKey -> userKey != null && !userKey.isBlank())
                .toList());
        Map<String, ScheduleCompletion> completionsByUserKey = new LinkedHashMap<>();
        for (AutoParticipantStrategy strategy : strategies) {
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            ProfilePolicy policy = policyForExecution(
                    strategy,
                    policy(profilePolicies, strategy.profileType())
            );
            if (!isDecisionEnabled(policy)) {
                continue;
            }
            int intervalSeconds = intervalSeconds(strategy.profileType(), policy);
            LocalDateTime nextRunAt = now.plusSeconds(
                    intervalSeconds + spreadSeconds(strategy.userKey(), intervalSeconds)
            );
            completionsByUserKey.putIfAbsent(
                    strategy.userKey(),
                    new ScheduleCompletion(
                            strategy.userKey(),
                            strategy.profileType(),
                            nextRunAt,
                            intervalSeconds,
                            priority(strategy.profileType(), policy)
                    )
            );
        }
        List<ScheduleCompletion> completions = List.copyOf(completionsByUserKey.values());
        for (int start = 0; start < completions.size(); start += COMPLETION_ROW_CHUNK_SIZE) {
            int end = Math.min(completions.size(), start + COMPLETION_ROW_CHUNK_SIZE);
            completed += completeStrategyChunk(completions.subList(start, end), now);
        }
        return completed;
    }

    private int completeStrategyChunk(List<ScheduleCompletion> completions, LocalDateTime now) {
        if (completions.isEmpty()) {
            return 0;
        }
        String profileTypeCases = casePlaceholders(completions.size());
        String nextRunAtCases = casePlaceholders(completions.size());
        String intervalCases = casePlaceholders(completions.size());
        String priorityCases = casePlaceholders(completions.size());
        String userKeyPlaceholders = completions.stream()
                .map(completion -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
                update stock_auto_participant_order_schedule
                   set profile_type = case user_key %s else profile_type end,
                       last_run_at = ?,
                       next_run_at = case user_key %s else next_run_at end,
                       lease_until = null,
                       lease_owner = null,
                       run_interval_seconds = case user_key %s else run_interval_seconds end,
                       priority = case user_key %s else priority end,
                       updated_at = ?
                 where user_key in (%s)
                   and lease_owner = ?
                """.formatted(
                profileTypeCases,
                nextRunAtCases,
                intervalCases,
                priorityCases,
                userKeyPlaceholders
        );
        List<Object> parameters = new ArrayList<>(completions.size() * 9 + 3);
        completions.forEach(completion -> {
            parameters.add(completion.userKey());
            parameters.add(completion.profileType().name());
        });
        parameters.add(now);
        completions.forEach(completion -> {
            parameters.add(completion.userKey());
            parameters.add(completion.nextRunAt());
        });
        completions.forEach(completion -> {
            parameters.add(completion.userKey());
            parameters.add(completion.intervalSeconds());
        });
        completions.forEach(completion -> {
            parameters.add(completion.userKey());
            parameters.add(completion.priority());
        });
        parameters.add(now);
        completions.forEach(completion -> parameters.add(completion.userKey()));
        parameters.add(leaseOwner);
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    private String casePlaceholders(int rowCount) {
        return java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(index -> "when ? then ?")
                .collect(Collectors.joining(" "));
    }

    private int ensureScheduleEntries(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        Map<String, ScheduleDefinition> definitionsByUserKey = new LinkedHashMap<>();
        List<String> disabledUserKeys = new ArrayList<>();
        for (AutoParticipantStrategy strategy : strategies) {
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            ProfilePolicy policy = policyForExecution(
                    strategy,
                    policy(profilePolicies, strategy.profileType())
            );
            if (!isDecisionEnabled(policy)) {
                disabledUserKeys.add(strategy.userKey());
                continue;
            }
            int intervalSeconds = intervalSeconds(strategy.profileType(), policy);
            int priority = priority(strategy.profileType(), policy);
            definitionsByUserKey.putIfAbsent(
                    strategy.userKey(),
                    new ScheduleDefinition(strategy.userKey(), strategy.profileType(), intervalSeconds, priority)
            );
        }
        deleteSchedules(disabledUserKeys);
        if (definitionsByUserKey.isEmpty()) {
            return 0;
        }
        List<ScheduleDefinition> definitions = List.copyOf(definitionsByUserKey.values());
        Map<String, ScheduleMetadata> existingSchedules = findExistingSchedules(
                definitions.stream().map(ScheduleDefinition::userKey).toList()
        );
        List<ScheduleDefinition> missing = definitions.stream()
                .filter(definition -> !existingSchedules.containsKey(definition.userKey()))
                .toList();
        List<ScheduleDefinition> changed = definitions.stream()
                .filter(definition -> {
                    ScheduleMetadata existing = existingSchedules.get(definition.userKey());
                    return existing != null && !existing.matches(
                            definition.profileType(),
                            definition.intervalSeconds(),
                            definition.priority()
                    );
                })
                .toList();
        int scheduled = insertScheduleDefinitions(missing, now);
        updateScheduleMetadata(changed, now);
        return scheduled;
    }

    private int insertScheduleDefinitions(List<ScheduleDefinition> definitions, LocalDateTime now) {
        int inserted = 0;
        for (int start = 0; start < definitions.size(); start += SCHEDULE_WRITE_ROW_CHUNK_SIZE) {
            int end = Math.min(definitions.size(), start + SCHEDULE_WRITE_ROW_CHUNK_SIZE);
            List<ScheduleDefinition> chunk = definitions.subList(start, end);
            String values = java.util.stream.IntStream.range(0, chunk.size())
                    .mapToObj(index -> "(?, ?, ?, null, null, null, ?, ?, ?, ?)")
                    .collect(Collectors.joining(", "));
            String sql = mysql
                    ? """
                      insert ignore into stock_auto_participant_order_schedule(
                          user_key, profile_type, next_run_at, last_run_at,
                          lease_until, lease_owner, run_interval_seconds, priority, created_at, updated_at
                      ) values %s
                      """.formatted(values)
                    : """
                      merge into stock_auto_participant_order_schedule(
                          user_key, profile_type, next_run_at, last_run_at,
                          lease_until, lease_owner, run_interval_seconds, priority, created_at, updated_at
                      ) key(user_key) values %s
                      """.formatted(values);
            List<Object> parameters = new ArrayList<>(chunk.size() * 7);
            for (ScheduleDefinition definition : chunk) {
                parameters.add(definition.userKey());
                parameters.add(definition.profileType().name());
                parameters.add(now);
                parameters.add(definition.intervalSeconds());
                parameters.add(definition.priority());
                parameters.add(now);
                parameters.add(now);
            }
            inserted += jdbcTemplate.update(sql, parameters.toArray());
        }
        return inserted;
    }

    private void updateScheduleMetadata(List<ScheduleDefinition> definitions, LocalDateTime now) {
        for (int start = 0; start < definitions.size(); start += SCHEDULE_WRITE_ROW_CHUNK_SIZE) {
            int end = Math.min(definitions.size(), start + SCHEDULE_WRITE_ROW_CHUNK_SIZE);
            updateScheduleMetadataChunk(definitions.subList(start, end), now);
        }
    }

    private int updateScheduleMetadataChunk(List<ScheduleDefinition> definitions, LocalDateTime now) {
        if (definitions.isEmpty()) {
            return 0;
        }
        String profileTypeCases = casePlaceholders(definitions.size());
        String intervalCases = casePlaceholders(definitions.size());
        String priorityCases = casePlaceholders(definitions.size());
        String userKeyPlaceholders = definitions.stream()
                .map(definition -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
                update stock_auto_participant_order_schedule
                   set profile_type = case user_key %s else profile_type end,
                       run_interval_seconds = case user_key %s else run_interval_seconds end,
                       priority = case user_key %s else priority end,
                       updated_at = ?
                 where user_key in (%s)
                """.formatted(profileTypeCases, intervalCases, priorityCases, userKeyPlaceholders);
        List<Object> parameters = new ArrayList<>(definitions.size() * 7 + 1);
        definitions.forEach(definition -> {
            parameters.add(definition.userKey());
            parameters.add(definition.profileType().name());
        });
        definitions.forEach(definition -> {
            parameters.add(definition.userKey());
            parameters.add(definition.intervalSeconds());
        });
        definitions.forEach(definition -> {
            parameters.add(definition.userKey());
            parameters.add(definition.priority());
        });
        parameters.add(now);
        definitions.forEach(definition -> parameters.add(definition.userKey()));
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    private Map<String, ScheduleMetadata> findExistingSchedules(List<String> userKeys) {
        if (userKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, ScheduleMetadata> schedules = new LinkedHashMap<>();
        for (int start = 0; start < userKeys.size(); start += SCHEDULE_QUERY_ROW_CHUNK_SIZE) {
            int end = Math.min(userKeys.size(), start + SCHEDULE_QUERY_ROW_CHUNK_SIZE);
            findExistingScheduleChunk(userKeys.subList(start, end)).forEach(schedules::put);
        }
        return Map.copyOf(schedules);
    }

    private Map<String, ScheduleMetadata> findExistingScheduleChunk(List<String> userKeys) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select user_key, profile_type, run_interval_seconds, priority
                        from stock_auto_participant_order_schedule
                        where user_key in (:userKeys)
                        """
                )
                .param("userKeys", userKeys)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("user_key"),
                        new ScheduleMetadata(
                                rs.getString("profile_type"),
                                rs.getInt("run_interval_seconds"),
                                rs.getInt("priority")
                        )
                ))
                .list()
                .stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private record ScheduleDefinition(
            String userKey,
            AutoParticipantProfileType profileType,
            int intervalSeconds,
            int priority
    ) {
    }

    private record ScheduleMetadata(String profileType, int intervalSeconds, int priority) {

        private boolean matches(AutoParticipantProfileType expectedProfileType, int expectedIntervalSeconds, int expectedPriority) {
            return expectedProfileType.name().equals(profileType)
                    && intervalSeconds == expectedIntervalSeconds
                    && priority == expectedPriority;
        }
    }

    private record ScheduleCompletion(
            String userKey,
            AutoParticipantProfileType profileType,
            LocalDateTime nextRunAt,
            int intervalSeconds,
            int priority
    ) {
    }

    private List<String> findDueUserKeys(List<String> userKeys, LocalDateTime now) {
        if (userKeys.isEmpty()) {
            return List.of();
        }
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select user_key
                        from stock_auto_participant_order_schedule
                        where user_key in (:userKeys)
                          and next_run_at <= :now
                          and (lease_until is null or lease_until <= :now)
                        order by priority desc, next_run_at asc, user_key asc
                        limit :limit
                        """
                )
                .param("userKeys", userKeys)
                .param("now", now)
                .param("limit", Math.max(1, dueLimitPerSymbol))
                .query(String.class)
                .list();
    }

    private Set<String> findClaimedUserKeys(List<String> userKeys, LocalDateTime now) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select user_key
                          from stock_auto_participant_order_schedule
                         where user_key in (:userKeys)
                           and lease_owner = :leaseOwner
                           and lease_until > :now
                        """
                )
                .param("userKeys", userKeys)
                .param("leaseOwner", leaseOwner)
                .param("now", now)
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, AutoParticipantStrategy> strategyByUserKey(List<AutoParticipantStrategy> strategies) {
        Map<String, AutoParticipantStrategy> byUserKey = new LinkedHashMap<>();
        for (AutoParticipantStrategy strategy : strategies) {
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            byUserKey.putIfAbsent(strategy.userKey(), strategy);
        }
        return byUserKey;
    }

    private int intervalSeconds(AutoParticipantProfileType profileType, ProfilePolicy policy) {
        double frequencyMultiplier = policy.executionPolicy().decisionFrequencyMultiplier();
        if (frequencyMultiplier <= 0) {
            throw new IllegalArgumentException("Disabled decision policy must not be scheduled: " + profileType);
        }
        int rawInterval = (int) Math.round(Math.max(1, baseIntervalSeconds) / frequencyMultiplier);
        return Math.clamp(rawInterval, Math.max(1, minIntervalSeconds), Math.max(Math.max(1, minIntervalSeconds), maxIntervalSeconds));
    }

    private boolean isDecisionEnabled(ProfilePolicy policy) {
        return policy != null
                && policy.executionPolicy().decisionFrequencyMultiplier() > 0
                && policy.executionPolicy().ordersPerDecisionMultiplier() > 0;
    }

    private int deleteSchedules(List<String> userKeys) {
        if (userKeys.isEmpty()) {
            return 0;
        }
        List<String> distinctUserKeys = userKeys.stream().distinct().toList();
        int deleted = 0;
        for (int start = 0; start < distinctUserKeys.size(); start += SCHEDULE_WRITE_ROW_CHUNK_SIZE) {
            int end = Math.min(distinctUserKeys.size(), start + SCHEDULE_WRITE_ROW_CHUNK_SIZE);
            deleted += JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                    .sql("delete from stock_auto_participant_order_schedule where user_key in (:userKeys)")
                    .param("userKeys", distinctUserKeys.subList(start, end))
                    .update();
        }
        return deleted;
    }

    private int priority(AutoParticipantProfileType profileType, ProfilePolicy policy) {
        double activity = policy.executionPolicy().ordersPerDecisionMultiplier()
                + policy.noiseWeight()
                + policy.momentumWeight()
                + policy.herdingWeight();
        if (profileType == AutoParticipantProfileType.MARKET_MAKER) {
            activity += 0.8;
        }
        return Math.clamp((int) Math.round(activity * 25), 1, 100);
    }

    private long spreadSeconds(String userKey, int intervalSeconds) {
        int maxSpread = Math.min(Math.max(0, spreadSeconds), Math.max(0, intervalSeconds - 1));
        if (maxSpread <= 0) {
            return 0;
        }
        return Math.floorMod(userKey.hashCode(), maxSpread + 1);
    }

    private ProfilePolicy policy(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            AutoParticipantProfileType profileType
    ) {
        ProfilePolicy policy = profilePolicies.get(profileType);
        return policy == null ? profilePolicies.get(AutoParticipantProfileType.defaultType()) : policy;
    }

    private ProfilePolicy policyForExecution(
            AutoParticipantStrategy strategy,
            ProfilePolicy configuredPolicy
    ) {
        if (configuredPolicy == null || strategy == null) {
            return configuredPolicy;
        }
        if (strategy.behaviorModelVersion()
                == stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion.V1) {
            return configuredPolicy.forLegacyExecution();
        }
        return configuredPolicy;
    }

    private void validateRange(String propertyName, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(
                    "stock.batch.auto-market.%s must be between %d and %d: %d"
                            .formatted(propertyName, minimum, maximum, value)
            );
        }
    }
}
