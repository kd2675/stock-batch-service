package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

@Component
@RequiredArgsConstructor
class AutoParticipantOrderScheduleService {

    private final JdbcTemplate jdbcTemplate;

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
        Map<String, AutoParticipantStrategy> strategyByUserKey = strategyByUserKey(strategies);
        if (seedMissingSchedules) {
            ensureScheduleEntries(strategies, profilePolicies, now);
        }
        List<String> dueUserKeys = findDueUserKeys(strategyByUserKey.keySet().stream().toList(), now);
        if (dueUserKeys.isEmpty()) {
            return List.of();
        }
        List<AutoParticipantStrategy> claimedStrategies = new ArrayList<>();
        LocalDateTime leaseUntil = now.plusSeconds(Math.max(1, leaseSeconds));
        for (String userKey : dueUserKeys) {
            AutoParticipantStrategy strategy = strategyByUserKey.get(userKey);
            if (strategy == null) {
                continue;
            }
            int updatedRows = jdbcTemplate.update(
                    """
                    update stock_auto_participant_order_schedule
                    set lease_until = ?,
                        lease_owner = ?,
                        updated_at = ?
                    where user_key = ?
                      and next_run_at <= ?
                      and (lease_until is null or lease_until <= ?)
                    """,
                    leaseUntil,
                    leaseOwner,
                    now,
                    userKey,
                    now,
                    now
            );
            if (updatedRows > 0) {
                claimedStrategies.add(strategy);
            }
        }
        return claimedStrategies;
    }

    int completeStrategies(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        int completed = 0;
        for (AutoParticipantStrategy strategy : strategies) {
            ProfilePolicy policy = policy(profilePolicies, strategy.profileType());
            int intervalSeconds = intervalSeconds(strategy.profileType(), policy);
            LocalDateTime nextRunAt = now.plusSeconds(intervalSeconds + spreadSeconds(strategy.userKey(), intervalSeconds));
            completed += jdbcTemplate.update(
                    """
                    update stock_auto_participant_order_schedule
                    set profile_type = ?,
                        last_run_at = ?,
                        next_run_at = ?,
                        lease_until = null,
                        lease_owner = null,
                        run_interval_seconds = ?,
                        priority = ?,
                        updated_at = ?
                    where user_key = ?
                      and lease_owner = ?
                    """,
                    strategy.profileType().name(),
                    now,
                    nextRunAt,
                    intervalSeconds,
                    priority(strategy.profileType(), policy),
                    now,
                    strategy.userKey(),
                    leaseOwner
            );
        }
        return completed;
    }

    private int ensureScheduleEntries(
            List<AutoParticipantStrategy> strategies,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        int scheduled = 0;
        Map<String, ScheduleMetadata> existingSchedules = findExistingSchedules(strategies);
        for (AutoParticipantStrategy strategy : strategies) {
            ProfilePolicy policy = policy(profilePolicies, strategy.profileType());
            int intervalSeconds = intervalSeconds(strategy.profileType(), policy);
            int priority = priority(strategy.profileType(), policy);
            ScheduleMetadata existing = existingSchedules.get(strategy.userKey());
            if (existing == null) {
                insertSchedule(strategy, intervalSeconds, priority, now);
                scheduled++;
                continue;
            }
            if (existing.matches(strategy.profileType(), intervalSeconds, priority)) {
                continue;
            }
            updateScheduleMetadata(strategy, intervalSeconds, priority, now);
        }
        return scheduled;
    }

    private void insertSchedule(
            AutoParticipantStrategy strategy,
            int intervalSeconds,
            int priority,
            LocalDateTime now
    ) {
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_auto_participant_order_schedule(
                        user_key, profile_type, next_run_at, last_run_at,
                        lease_until, lease_owner, run_interval_seconds, priority, created_at, updated_at
                    )
                    values (?, ?, ?, null, null, null, ?, ?, ?, ?)
                    """,
                    strategy.userKey(),
                    strategy.profileType().name(),
                    now,
                    intervalSeconds,
                    priority,
                    now,
                    now
            );
        } catch (DuplicateKeyException ignored) {
            // Another batch process seeded this schedule first. The next run refreshes metadata if needed.
        }
    }

    private void updateScheduleMetadata(
            AutoParticipantStrategy strategy,
            int intervalSeconds,
            int priority,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                update stock_auto_participant_order_schedule
                set profile_type = ?,
                    run_interval_seconds = ?,
                    priority = ?,
                    updated_at = ?
                where user_key = ?
                  and (profile_type <> ? or run_interval_seconds <> ? or priority <> ?)
                """,
                strategy.profileType().name(),
                intervalSeconds,
                priority,
                now,
                strategy.userKey(),
                strategy.profileType().name(),
                intervalSeconds,
                priority
        );
    }

    private Map<String, ScheduleMetadata> findExistingSchedules(List<AutoParticipantStrategy> strategies) {
        List<String> userKeys = strategies.stream()
                .map(AutoParticipantStrategy::userKey)
                .filter(userKey -> userKey != null && !userKey.isBlank())
                .distinct()
                .toList();
        if (userKeys.isEmpty()) {
            return Map.of();
        }
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

    private record ScheduleMetadata(String profileType, int intervalSeconds, int priority) {

        private boolean matches(AutoParticipantProfileType expectedProfileType, int expectedIntervalSeconds, int expectedPriority) {
            return expectedProfileType.name().equals(profileType)
                    && intervalSeconds == expectedIntervalSeconds
                    && priority == expectedPriority;
        }
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
        double activityMultiplier = Math.max(0.25, policy.orderMultiplier());
        double patienceMultiplier = Math.max(0.25, policy.orderTtlMultiplier());
        int rawInterval = (int) Math.round(Math.max(1, baseIntervalSeconds) * patienceMultiplier / activityMultiplier);
        return Math.clamp(rawInterval, Math.max(1, minIntervalSeconds), Math.max(Math.max(1, minIntervalSeconds), maxIntervalSeconds));
    }

    private int priority(AutoParticipantProfileType profileType, ProfilePolicy policy) {
        double activity = policy.orderMultiplier() + policy.noiseWeight() + policy.momentumWeight() + policy.herdingWeight();
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
}
