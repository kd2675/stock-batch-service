package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoOrder;

@Component
class InstitutionOrderIntentRepository {

    private static final int EXTERNAL_DEPTH_LEVELS = 5;
    private static final int MAX_FAILURE_ATTEMPTS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    InstitutionOrderIntentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    List<IntentReference> findPendingIntents(
            LocalDate simulationTradeDate,
            int limit
    ) {
        return jdbcClient.sql(
                        """
                        select intent.decision_run_id, intent.symbol
                          from stock_institution_order_intent intent
                          join stock_institution_decision_run decision_run
                            on decision_run.id = intent.decision_run_id
                           and decision_run.simulation_trade_date = :simulationTradeDate
                         where intent.status = 'PENDING'
                         order by intent.created_at asc,
                                  intent.decision_run_id asc,
                                  intent.symbol asc
                         limit :limit
                        """
                )
                .param("simulationTradeDate", simulationTradeDate)
                .param("limit", Math.clamp(limit, 1, 100))
                .query((rs, rowNum) -> new IntentReference(
                        rs.getLong("decision_run_id"),
                        rs.getString("symbol")
                ))
                .list();
    }

    int rejectStalePendingIntents(
            LocalDate simulationTradeDate,
            LocalDateTime rejectedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_institution_order_intent intent
                   set status = 'REJECTED',
                       submission_reason = 'STALE_SIMULATION_TRADE_DATE',
                       updated_at = ?
                 where intent.status = 'PENDING'
                   and not exists (
                       select 1
                         from stock_institution_decision_run decision_run
                        where decision_run.id = intent.decision_run_id
                          and decision_run.simulation_trade_date = ?
                   )
                """,
                rejectedAt,
                simulationTradeDate
        );
    }

    Optional<InstitutionOrderIntent> lockIntent(
            long decisionRunId,
            String symbol,
            LocalDate simulationTradeDate
    ) {
        return jdbcClient.sql(
                        """
                        select intent.decision_run_id,
                               intent.symbol,
                               intent.portfolio_id,
                               intent.participant_id,
                               intent.account_id,
                               intent.side,
                               intent.requested_quantity,
                               intent.planned_amount,
                               intent.reference_daily_volume,
                               intent.execution_aggression_pressure,
                               intent.policy_version,
                               decision_run.deterministic_seed,
                               portfolio.status as portfolio_status,
                               portfolio.execution_mode,
                               account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id as account_self_trade_group_id,
                               participant.status as participant_status,
                               participant.participant_type,
                               participant.self_trade_group_id as participant_self_trade_group_id,
                               participant_account.account_role,
                               participant_account.status as mapping_status
                          from stock_institution_order_intent intent
                          join stock_institution_decision_run decision_run
                            on decision_run.id = intent.decision_run_id
                           and decision_run.portfolio_id = intent.portfolio_id
                           and decision_run.execution_mode = 'PILOT'
                           and decision_run.status = 'COMPLETED'
                           and decision_run.policy_version = intent.policy_version
                          join stock_institution_portfolio portfolio
                            on portfolio.id = intent.portfolio_id
                           and portfolio.participant_id = intent.participant_id
                           and portfolio.account_id = intent.account_id
                           and portfolio.policy_version = intent.policy_version
                          join stock_account account
                            on account.id = intent.account_id
                          join stock_market_participant participant
                            on participant.id = intent.participant_id
                          join stock_market_participant_account participant_account
                            on participant_account.participant_id = intent.participant_id
                           and participant_account.account_id = intent.account_id
                           and participant_account.effective_from <= :simulationTradeDate
                           and (
                               participant_account.effective_to is null
                               or participant_account.effective_to >= :simulationTradeDate
                           )
                         where intent.decision_run_id = :decisionRunId
                           and intent.symbol = :symbol
                           and intent.status = 'PENDING'
                         for update
                        """
                )
                .param("simulationTradeDate", simulationTradeDate)
                .param("decisionRunId", decisionRunId)
                .param("symbol", symbol)
                .query((rs, rowNum) -> new InstitutionOrderIntent(
                        rs.getLong("decision_run_id"),
                        rs.getString("symbol"),
                        rs.getLong("portfolio_id"),
                        rs.getLong("participant_id"),
                        rs.getLong("account_id"),
                        rs.getString("side"),
                        rs.getLong("requested_quantity"),
                        rs.getBigDecimal("planned_amount"),
                        rs.getLong("reference_daily_volume"),
                        rs.getBigDecimal("execution_aggression_pressure"),
                        rs.getLong("policy_version"),
                        rs.getLong("deterministic_seed"),
                        rs.getString("portfolio_status"),
                        rs.getString("execution_mode"),
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("account_self_trade_group_id"),
                        rs.getString("participant_status"),
                        rs.getString("participant_type"),
                        rs.getString("participant_self_trade_group_id"),
                        rs.getString("account_role"),
                        rs.getString("mapping_status")
                ))
                .optional();
    }

    InstitutionExternalBook findExternalBook(InstitutionOrderIntent intent) {
        List<PriceLevel> buyLevels = findDepthLevels(
                intent.symbol(),
                "BUY",
                intent.accountId(),
                intent.accountSelfTradeGroupId()
        );
        List<PriceLevel> sellLevels = findDepthLevels(
                intent.symbol(),
                "SELL",
                intent.accountId(),
                intent.accountSelfTradeGroupId()
        );
        return new InstitutionExternalBook(
                buyLevels.isEmpty() ? null : buyLevels.getFirst().price(),
                sellLevels.isEmpty() ? null : sellLevels.getFirst().price(),
                depthQuantity(buyLevels),
                depthQuantity(sellLevels)
        );
    }

    private List<PriceLevel> findDepthLevels(
            String symbol,
            String side,
            long accountId,
            String selfTradeGroupId
    ) {
        String priceOrder = "BUY".equals(side) ? "desc" : "asc";
        return jdbcClient.sql(
                        """
                        select limit_price,
                               sum(quantity - filled_quantity) as remaining_quantity
                          from stock_order
                         where symbol = :symbol
                           and market_type = 'ORDER_BOOK'
                           and side = :side
                           and account_id <> :accountId
                           and order_type = 'LIMIT'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                           and limit_price is not null
                           and coalesce(
                                   self_trade_group_id,
                                   concat('ACCOUNT:', account_id)
                               ) <> :selfTradeGroupId
                         group by limit_price
                         order by limit_price %s
                         limit :depthLevels
                        """.formatted(priceOrder)
                )
                .param("symbol", symbol)
                .param("side", side)
                .param("accountId", accountId)
                .param("selfTradeGroupId", selfTradeGroupId)
                .param("depthLevels", EXTERNAL_DEPTH_LEVELS)
                .query((rs, rowNum) -> new PriceLevel(
                        rs.getBigDecimal("limit_price"),
                        rs.getLong("remaining_quantity")
                ))
                .list();
    }

    void markSubmitted(
            InstitutionOrderIntent intent,
            InstitutionOrderExecutionPlan plan,
            LocalDate simulationTradeDate,
            LocalDateTime submittedAt
    ) {
        Long orderId = jdbcClient.sql(
                        """
                        select strategy_origin.order_id
                          from stock_order_strategy_origin strategy_origin
                          join stock_order order_row
                            on order_row.id = strategy_origin.order_id
                           and order_row.symbol = :symbol
                           and order_row.account_id = :accountId
                         where strategy_origin.origin_type = 'INSTITUTIONAL_INVESTOR'
                           and strategy_origin.decision_run_id = :decisionRunId
                           and strategy_origin.portfolio_id = :portfolioId
                        """
                )
                .param("symbol", intent.symbol())
                .param("accountId", intent.accountId())
                .param("decisionRunId", intent.decisionRunId())
                .param("portfolioId", intent.portfolioId())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Submitted institution order has no strategy-origin row"
                ));
        int intentUpdated = jdbcTemplate.update(
                """
                update stock_institution_order_intent
                   set status = 'SUBMITTED',
                       submitted_order_id = ?,
                       submitted_price = ?,
                       submitted_quantity = ?,
                       submission_reason = ?,
                       updated_at = ?,
                       submitted_at = ?
                 where decision_run_id = ?
                   and symbol = ?
                   and status = 'PENDING'
                """,
                orderId,
                plan.price(),
                plan.quantity(),
                plan.reason(),
                submittedAt,
                submittedAt,
                intent.decisionRunId(),
                intent.symbol()
        );
        if (intentUpdated != 1) {
            throw new IllegalStateException("Institution order intent submission audit update failed");
        }
        BigDecimal submittedAmount = plan.price()
                .multiply(BigDecimal.valueOf(plan.quantity()))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        int budgetUpdated = jdbcTemplate.update(
                """
                update stock_institution_daily_budget
                   set submitted_buy_amount = submitted_buy_amount + ?,
                       submitted_sell_amount = submitted_sell_amount + ?,
                       version = version + 1,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and portfolio_id = ?
                   and symbol = ?
                   and policy_version = ?
                """,
                "BUY".equals(intent.side()) ? submittedAmount : BigDecimal.ZERO,
                "SELL".equals(intent.side()) ? submittedAmount : BigDecimal.ZERO,
                submittedAt,
                simulationTradeDate,
                intent.portfolioId(),
                intent.symbol(),
                intent.policyVersion()
        );
        if (budgetUpdated != 1) {
            throw new IllegalStateException("Institution submitted budget update failed");
        }
    }

    void markRejected(
            InstitutionOrderIntent intent,
            String reason,
            LocalDateTime rejectedAt
    ) {
        int updated = jdbcTemplate.update(
                """
                update stock_institution_order_intent
                   set status = 'REJECTED',
                       submission_reason = ?,
                       updated_at = ?
                 where decision_run_id = ?
                   and symbol = ?
                   and status = 'PENDING'
                """,
                truncate(reason),
                rejectedAt,
                intent.decisionRunId(),
                intent.symbol()
        );
        if (updated != 1) {
            throw new IllegalStateException("Institution order intent rejection audit failed");
        }
    }

    FailureResult recordFailure(
            IntentReference reference,
            String reason,
            LocalDateTime failedAt
    ) {
        int updated = jdbcTemplate.update(
                """
                update stock_institution_order_intent
                   set attempt_count = least(?, attempt_count + 1),
                       status = case
                           when attempt_count + 1 >= ? then 'FAILED'
                           else 'PENDING'
                       end,
                       submission_reason = ?,
                       updated_at = ?
                 where decision_run_id = ?
                   and symbol = ?
                   and status = 'PENDING'
                """,
                MAX_FAILURE_ATTEMPTS,
                MAX_FAILURE_ATTEMPTS,
                truncate(reason),
                failedAt,
                reference.decisionRunId(),
                reference.symbol()
        );
        if (updated == 0) {
            return FailureResult.NOT_PENDING;
        }
        IntentFailureRow failure = jdbcClient.sql(
                        """
                        select portfolio_id, status, attempt_count
                          from stock_institution_order_intent
                         where decision_run_id = :decisionRunId
                           and symbol = :symbol
                        """
                )
                .param("decisionRunId", reference.decisionRunId())
                .param("symbol", reference.symbol())
                .query((rs, rowNum) -> new IntentFailureRow(
                        rs.getLong("portfolio_id"),
                        rs.getString("status"),
                        rs.getInt("attempt_count")
                ))
                .single();
        return new FailureResult(
                true,
                "FAILED".equals(failure.status()),
                failure.attemptCount(),
                failure.portfolioId()
        );
    }

    void suspendPilotPortfolio(long portfolioId, LocalDateTime suspendedAt) {
        PilotSuspensionRow portfolio = jdbcClient.sql(
                        """
                        select portfolio_code, execution_mode, status, policy_version
                          from stock_institution_portfolio
                         where id = ?
                         for update
                        """
                )
                .param(portfolioId)
                .query((rs, rowNum) -> new PilotSuspensionRow(
                        rs.getString("portfolio_code"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Institution portfolio disappeared during automatic suspension: "
                                + portfolioId
                ));
        if ("SUSPENDED".equals(portfolio.status())) {
            return;
        }
        if (!"PILOT".equals(portfolio.executionMode())
                || !"ACTIVE".equals(portfolio.status())) {
            throw new IllegalStateException(
                    "Only an active institution PILOT can be automatically suspended"
            );
        }
        long nextPolicyVersion = Math.addExact(portfolio.policyVersion(), 1L);
        int updated = jdbcTemplate.update(
                """
                update stock_institution_portfolio
                   set status = 'SUSPENDED',
                       next_decision_at = null,
                       policy_version = ?,
                       updated_at = ?
                 where id = ?
                   and status = 'ACTIVE'
                   and execution_mode = 'PILOT'
                   and policy_version = ?
                """,
                nextPolicyVersion,
                suspendedAt,
                portfolioId,
                portfolio.policyVersion()
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Institution automatic suspension count mismatch: " + updated
            );
        }
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = ?
                   and status in ('SCHEDULED', 'ACTIVE')
                """,
                suspendedAt,
                portfolio.portfolioCode()
        );
        int policyInserted = jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'INSTITUTIONAL_PORTFOLIO', ?, ?, ?,
                    'ACTIVE', ?,
                    'Automatic suspension after three institution order-intent failures',
                    'STOCK_BATCH', ?, ?
                )
                """,
                portfolio.portfolioCode(),
                nextPolicyVersion,
                suspendedAt.toLocalDate(),
                "{\"transition\":\"PILOT_AUTOMATIC_SUSPEND\",\"status\":\"SUSPENDED\"}",
                suspendedAt,
                suspendedAt
        );
        if (policyInserted != 1) {
            throw new IllegalStateException(
                    "Institution automatic-suspension policy audit insert failed"
            );
        }
    }

    List<AutoOrder> findOpenPortfolioAccountOrders(long portfolioId) {
        return jdbcClient.sql(
                        """
                        select stock_order.id,
                               stock_order.account_id,
                               stock_order.symbol,
                               stock_order.side,
                               stock_order.quantity,
                               stock_order.filled_quantity,
                               stock_order.reserved_cash,
                               stock_order.limit_price,
                               stock_order.expires_at,
                               stock_order.created_at
                          from stock_institution_portfolio portfolio
                          join stock_order
                            on stock_order.account_id = portfolio.account_id
                         where portfolio.id = ?
                           and stock_order.market_type = 'ORDER_BOOK'
                           and stock_order.status in ('PENDING', 'PARTIALLY_FILLED')
                           and stock_order.quantity > stock_order.filled_quantity
                         order by stock_order.account_id asc,
                                  stock_order.symbol asc,
                                  stock_order.id asc
                        """
                )
                .param(portfolioId)
                .query((rs, rowNum) -> new AutoOrder(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash"),
                        rs.getBigDecimal("limit_price"),
                        null,
                        null,
                        rs.getObject("expires_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)
                ))
                .list();
    }

    private long depthQuantity(List<PriceLevel> levels) {
        long total = 0L;
        for (PriceLevel level : levels) {
            if (level.quantity() > Long.MAX_VALUE - total) {
                return Long.MAX_VALUE;
            }
            total += Math.max(0L, level.quantity());
        }
        return total;
    }

    private String truncate(String value) {
        String normalized = value == null || value.isBlank()
                ? "UNKNOWN_INSTITUTION_ORDER_FAILURE"
                : value.trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    record IntentReference(long decisionRunId, String symbol) {
    }

    record FailureResult(
            boolean recorded,
            boolean terminal,
            int attemptCount,
            long portfolioId
    ) {
        static final FailureResult NOT_PENDING = new FailureResult(false, false, 0, 0L);
    }

    private record PriceLevel(BigDecimal price, long quantity) {
    }

    private record IntentFailureRow(long portfolioId, String status, int attemptCount) {
    }

    private record PilotSuspensionRow(
            String portfolioCode,
            String executionMode,
            String status,
            long policyVersion
    ) {
    }
}
