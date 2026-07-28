package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.v3.AutoParticipantDecisionUrgency;
import stock.batch.service.automarket.v3.AutoParticipantV3Policy;
import stock.batch.service.automarket.v3.SafeQuantityCalculator;
import stock.batch.service.automarket.v3.SafeQuantityLimit;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.StockOrderOriginType;

@Component
class AutoParticipantRiskGuardService {

    private static final int MAX_GUARD_ACCOUNTS = 100;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AutoParticipantOrderScheduleService scheduleService;
    private final AutoMarketOrderExecutor orderExecutor;
    private final AutoParticipantRiskGuardPolicy guardPolicy = new AutoParticipantRiskGuardPolicy();
    private final SafeQuantityCalculator quantityCalculator = new SafeQuantityCalculator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stock.market-session.close-time:18:00}")
    private LocalTime marketCloseTime = LocalTime.of(18, 0);

    AutoParticipantRiskGuardService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AutoParticipantOrderScheduleService scheduleService,
            AutoMarketOrderExecutor orderExecutor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.scheduleService = scheduleService;
        this.orderExecutor = orderExecutor;
    }

    int processDueGuards(LocalDateTime now) {
        List<Long> accountIds = scheduleService.findDueGuardAccountIds(now, MAX_GUARD_ACCOUNTS);
        if (accountIds.isEmpty()) {
            return 0;
        }
        Integer processed = transactionTemplate.execute(status -> processClaimedAccounts(accountIds, now));
        return processed == null ? 0 : processed;
    }

    private int processClaimedAccounts(List<Long> accountIds, LocalDateTime now) {
        markCompletedPlans(accountIds, now);
        List<HeldPosition> positions = findHeldPositions(accountIds, now.toLocalDate());
        Map<Long, List<AutoMarketPlannedOrder>> ordersByAccount = new LinkedHashMap<>();
        Map<PlanKey, Attempt> attempts = new LinkedHashMap<>();
        long secondsToClose = Math.max(
                0L,
                Duration.between(now, now.toLocalDate().atTime(marketCloseTime)).toSeconds()
        );
        for (HeldPosition position : positions) {
            AutoParticipantRiskGuardPolicy.GuardDecision decision = guardPolicy.evaluate(
                    position.profileType(),
                    new AutoParticipantRiskGuardPolicy.GuardSnapshot(
                            secondsToClose,
                            Math.max(0L, Duration.between(position.positionUpdatedAt(), now).toSeconds()),
                            position.unrealizedReturn()
                    )
            );
            if (!decision.triggered()) {
                continue;
            }
            PlanKey planKey = new PlanKey(
                    now.toLocalDate(),
                    position.accountId(),
                    position.symbol(),
                    decision.urgency()
            );
            if (!position.orderable()) {
                attempts.put(planKey, Attempt.incomplete(position, decision, "MARKET_UNAVAILABLE"));
                continue;
            }
            if (position.availableQuantity() <= 0) {
                attempts.put(planKey, Attempt.incomplete(position, decision, "NO_AVAILABLE_QUANTITY"));
                continue;
            }
            BigDecimal price = marketableSellPrice(position);
            SafeQuantityLimit limit = quantityCalculator.calculate(
                    new SafeQuantityCalculator.LimitInput(
                            false,
                            true,
                            BigDecimal.ZERO,
                            price,
                            BigDecimal.ZERO,
                            position.maxOrderQuantity(),
                            position.availableQuantity(),
                            position.availableQuantity(),
                            position.availableQuantity(),
                            Long.MAX_VALUE,
                            position.availableQuantity(),
                            position.availableQuantity(),
                            Long.MAX_VALUE
                    )
            );
            long quantity = quantityCalculator.sample(
                    limit,
                    decision.urgency(),
                    position.behaviorSeed(),
                    now.toLocalDate(),
                    position.eventSequence() + 1L,
                    position.policy()
            ).quantity();
            if (quantity <= 0) {
                attempts.put(planKey, Attempt.incomplete(position, decision, "SAFE_QUANTITY_ZERO"));
                continue;
            }
            AutoMarketPlannedOrder order = new AutoMarketPlannedOrder(
                    position.accountId(),
                    position.symbol(),
                    "SELL",
                    price,
                    quantity,
                    null,
                    decision.reason().equals("SESSION_CLOSE")
                            ? stock.batch.service.automarket.profile.ProfileDecisionReason.SESSION_CLOSE
                            : stock.batch.service.automarket.profile.ProfileDecisionReason.RISK_LIMIT,
                    now.plusSeconds(60),
                    position.profileType(),
                    AutoParticipantBehaviorModelVersion.V3,
                    StockOrderOriginType.AUTO_PARTICIPANT,
                    null,
                    position.policyVersion(),
                    position.eventSequence() + 1L,
                    decision.urgency()
            );
            ordersByAccount.computeIfAbsent(position.accountId(), ignored -> new ArrayList<>()).add(order);
            attempts.put(planKey, Attempt.submitted(position, decision, quantity));
        }

        int generated = 0;
        for (Map.Entry<Long, List<AutoMarketPlannedOrder>> entry : ordersByAccount.entrySet()) {
            AutoParticipantOrderGenerationResult result = orderExecutor.placeOrders(entry.getValue());
            generated += result.generatedOrderCount();
            if (result.generatedOrderCount() == 0) {
                attempts.replaceAll((key, attempt) -> key.accountId() == entry.getKey()
                        && attempt.status().equals("SUBMITTED")
                        ? attempt.asIncomplete("ORDER_REJECTED")
                        : attempt);
            }
        }
        persistAttempts(attempts, now);
        advanceGuardEventSequences(attempts, now);
        scheduleService.completeGuards(accountIds, now);
        return generated;
    }

    private List<HeldPosition> findHeldPositions(List<Long> accountIds, LocalDate tradeDate) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select a.id as account_id, p.profile_type, p.behavior_seed,
                               h.symbol, h.quantity, h.reserved_quantity, h.average_price,
                               h.updated_at as position_updated_at,
                               d.policy_version, d.event_sequence, policy.policy_json,
                               c.max_order_quantity, c.enabled as auto_market_enabled,
                               i.enabled as instrument_enabled, i.tick_size, i.price_limit_rate,
                               m.enabled as market_enabled, m.market_status,
                               px.current_price, px.previous_close
                          from stock_account a
                          join stock_auto_participant p
                            on p.user_key = a.user_key
                           and p.enabled = true
                           and p.withdrawn_at is null
                          join stock_holding h
                            on h.account_id = a.id
                           and h.quantity > 0
                          join stock_auto_participant_daily_behavior_state d
                            on d.account_id = a.id
                           and d.simulation_trade_date = :tradeDate
                          join stock_auto_participant_policy_revision policy
                            on policy.policy_version = d.policy_version
                          left join stock_auto_market_config c on c.symbol = h.symbol
                          left join stock_order_book_instrument i on i.symbol = h.symbol
                          left join stock_order_book_market_config m on m.symbol = h.symbol
                          left join stock_price px on px.symbol = h.symbol
                         where a.id in (:accountIds)
                           and a.status = 'ACTIVE'
                         order by a.id asc, h.symbol asc
                        """
                )
                .param("tradeDate", tradeDate)
                .param("accountIds", accountIds)
                .query((rs, rowNum) -> new HeldPosition(
                        rs.getLong("account_id"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getObject("behavior_seed") == null
                                ? rs.getLong("account_id")
                                : rs.getLong("behavior_seed"),
                        rs.getString("symbol"),
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price"),
                        rs.getTimestamp("position_updated_at").toLocalDateTime(),
                        rs.getLong("policy_version"),
                        rs.getLong("event_sequence"),
                        AutoParticipantV3Policy.fromJson(
                                rs.getLong("policy_version"),
                                rs.getString("policy_json"),
                                objectMapper
                        ),
                        rs.getObject("max_order_quantity") == null
                                ? 0
                                : rs.getInt("max_order_quantity"),
                        rs.getBoolean("auto_market_enabled"),
                        rs.getBoolean("instrument_enabled"),
                        rs.getBigDecimal("tick_size"),
                        rs.getBoolean("market_enabled"),
                        rs.getString("market_status"),
                        rs.getBigDecimal("current_price"),
                        rs.getBigDecimal("previous_close"),
                        rs.getBigDecimal("price_limit_rate")
                ))
                .list();
    }

    private BigDecimal marketableSellPrice(HeldPosition position) {
        BigDecimal rawLower = position.previousClose().multiply(
                BigDecimal.ONE.subtract(position.priceLimitRate().movePointLeft(2))
        );
        BigDecimal ticks = rawLower.divide(position.tickSize(), 0, RoundingMode.CEILING);
        return ticks.multiply(position.tickSize()).max(position.tickSize());
    }

    private void persistAttempts(Map<PlanKey, Attempt> attempts, LocalDateTime now) {
        for (Map.Entry<PlanKey, Attempt> entry : attempts.entrySet()) {
            PlanKey key = entry.getKey();
            Attempt attempt = entry.getValue();
            int updated = jdbcTemplate.update(
                    """
                    update stock_auto_participant_liquidation_plan
                       set trigger_reason = ?,
                           status = ?,
                           target_quantity = greatest(target_quantity, ?),
                           submitted_quantity = submitted_quantity + ?,
                           remaining_quantity = ?,
                           attempt_count = attempt_count + 1,
                           next_retry_at = ?,
                           last_error = ?,
                           updated_at = ?
                     where simulation_trade_date = ?
                       and account_id = ?
                       and symbol = ?
                       and urgency = ?
                    """,
                    attempt.reason(),
                    attempt.status(),
                    attempt.targetQuantity(),
                    attempt.submittedQuantity(),
                    attempt.targetQuantity(),
                    now.plusSeconds(30),
                    attempt.error(),
                    now,
                    key.tradeDate(),
                    key.accountId(),
                    key.symbol(),
                    key.urgency().name()
            );
            if (updated == 0) {
                jdbcTemplate.update(
                        """
                        insert into stock_auto_participant_liquidation_plan(
                            simulation_trade_date, account_id, symbol, urgency, trigger_reason,
                            status, target_quantity, submitted_quantity, remaining_quantity,
                            attempt_count, next_retry_at, last_error, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                        """,
                        key.tradeDate(),
                        key.accountId(),
                        key.symbol(),
                        key.urgency().name(),
                        attempt.reason(),
                        attempt.status(),
                        attempt.targetQuantity(),
                        attempt.submittedQuantity(),
                        attempt.targetQuantity(),
                        now.plusSeconds(30),
                        attempt.error(),
                        now,
                        now
                );
            }
        }
    }

    private void markCompletedPlans(List<Long> accountIds, LocalDateTime now) {
        JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        update stock_auto_participant_liquidation_plan p
                           set status = 'COMPLETED',
                               remaining_quantity = 0,
                               next_retry_at = null,
                               last_error = null,
                               updated_at = :now
                         where p.account_id in (:accountIds)
                           and p.status in ('PENDING', 'SUBMITTED', 'INCOMPLETE')
                           and not exists (
                               select 1
                                 from stock_holding h
                                where h.account_id = p.account_id
                                  and h.symbol = p.symbol
                                  and h.quantity > 0
                           )
                        """
                )
                .param("now", now)
                .param("accountIds", accountIds)
                .update();
    }

    private void advanceGuardEventSequences(Map<PlanKey, Attempt> attempts, LocalDateTime now) {
        Map<Long, Long> nextSequenceByAccount = new LinkedHashMap<>();
        attempts.values().forEach(attempt -> nextSequenceByAccount.merge(
                attempt.position().accountId(),
                attempt.position().eventSequence() + 1L,
                Math::max
        ));
        for (Map.Entry<Long, Long> entry : nextSequenceByAccount.entrySet()) {
            jdbcTemplate.update(
                    """
                    update stock_auto_participant_daily_behavior_state
                       set event_sequence = greatest(event_sequence, ?),
                           last_decision_at = ?,
                           last_result_reason = 'RISK_GUARD',
                           optimistic_version = optimistic_version + 1,
                           updated_at = ?
                     where simulation_trade_date = ?
                       and account_id = ?
                    """,
                    entry.getValue(),
                    now,
                    now,
                    now.toLocalDate(),
                    entry.getKey()
            );
        }
    }

    private record HeldPosition(
            long accountId,
            AutoParticipantProfileType profileType,
            long behaviorSeed,
            String symbol,
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice,
            LocalDateTime positionUpdatedAt,
            long policyVersion,
            long eventSequence,
            AutoParticipantV3Policy policy,
            int maxOrderQuantity,
            boolean autoMarketEnabled,
            boolean instrumentEnabled,
            BigDecimal tickSize,
            boolean marketEnabled,
            String marketStatus,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate
    ) {
        long availableQuantity() {
            return Math.max(0L, quantity - reservedQuantity);
        }

        boolean orderable() {
            return autoMarketEnabled
                    && instrumentEnabled
                    && marketEnabled
                    && "OPEN".equals(marketStatus)
                    && maxOrderQuantity > 0
                    && tickSize != null
                    && tickSize.signum() > 0
                    && currentPrice != null
                    && currentPrice.signum() > 0
                    && previousClose != null
                    && previousClose.signum() > 0
                    && priceLimitRate != null;
        }

        double unrealizedReturn() {
            if (averagePrice == null || averagePrice.signum() <= 0 || currentPrice == null) {
                return 0.0;
            }
            return currentPrice.subtract(averagePrice)
                    .divide(averagePrice, 8, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    private record PlanKey(
            LocalDate tradeDate,
            long accountId,
            String symbol,
            AutoParticipantDecisionUrgency urgency
    ) {
    }

    private record Attempt(
            HeldPosition position,
            String reason,
            String status,
            long targetQuantity,
            long submittedQuantity,
            String error
    ) {
        static Attempt submitted(
                HeldPosition position,
                AutoParticipantRiskGuardPolicy.GuardDecision decision,
                long submittedQuantity
        ) {
            return new Attempt(
                    position,
                    decision.reason(),
                    "SUBMITTED",
                    position.quantity(),
                    submittedQuantity,
                    null
            );
        }

        static Attempt incomplete(
                HeldPosition position,
                AutoParticipantRiskGuardPolicy.GuardDecision decision,
                String error
        ) {
            return new Attempt(
                    position,
                    decision.reason(),
                    "INCOMPLETE",
                    position.quantity(),
                    0L,
                    error
            );
        }

        Attempt asIncomplete(String error) {
            return new Attempt(position, reason, "INCOMPLETE", targetQuantity, 0L, error);
        }
    }
}
