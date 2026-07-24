package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType;

class AutoParticipantFundingBudgetServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AutoParticipantFundingBudgetService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:funding_budget_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table stock_order (
                    id bigint primary key,
                    client_order_id varchar(80) not null unique
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant_funding_budget (
                    id bigint primary key,
                    account_id bigint not null,
                    budget_type varchar(20) not null,
                    source_symbol varchar(20),
                    granted_amount decimal(19,2) not null,
                    available_amount decimal(19,2) not null,
                    reserved_amount decimal(19,2) not null,
                    spent_amount decimal(19,2) not null,
                    expires_business_date date,
                    status varchar(20) not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table stock_auto_participant_order_budget (
                    order_id bigint not null,
                    budget_id bigint not null,
                    allocated_amount decimal(19,2) not null,
                    remaining_reserved_amount decimal(19,2) not null,
                    spent_amount decimal(19,2) not null,
                    released_amount decimal(19,2) not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    primary key(order_id, budget_id)
                )
                """);
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_funding_budget(
                    id, account_id, budget_type, source_symbol, granted_amount, available_amount,
                    reserved_amount, spent_amount, expires_business_date, status, updated_at
                ) values (1, 10, 'PAYDAY', null, 500, 500, 0, 0, ?, 'ACTIVE', ?)
                """,
                LocalDate.of(2027, 2, 1),
                LocalDateTime.of(2027, 1, 18, 0, 0)
        );
        service = new AutoParticipantFundingBudgetService(jdbcTemplate);
    }

    @Test
    void reserveThenPartialExecutionAndCancellation_reconcilesEveryAmount() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        LocalDateTime now = businessDate.atTime(9, 0);
        AutoMarketPlannedOrder first = budgetOrder();
        jdbcTemplate.update("insert into stock_order(id, client_order_id) values (101, 'order-101')");
        reserve(first, "order-101", businessDate, now);

        service.consumeOrderBudget(101L, new BigDecimal("100.00"), new BigDecimal("80.00"), now.plusSeconds(1));

        assertBudget("420.00", "0.00", "80.00", "ACTIVE");
        assertAllocation(101L, "0.00", "80.00", "20.00");

        AutoMarketPlannedOrder second = budgetOrder();
        jdbcTemplate.update("insert into stock_order(id, client_order_id) values (102, 'order-102')");
        reserve(second, "order-102", businessDate, now.plusSeconds(2));
        service.releaseCancelledOrderBudgets(List.of(102L), now.plusSeconds(3));

        assertBudget("420.00", "0.00", "80.00", "ACTIVE");
        assertAllocation(102L, "0.00", "0.00", "100.00");
        AutoParticipantFundingBudgetService.FundingBudgetReconciliationChunk reconciliation =
                service.validateReconciliationChunk(0L, 500);
        assertThat(List.of(
                reconciliation.processedCount(),
                Math.toIntExact(reconciliation.lastBudgetId()),
                reconciliation.finished() ? 1 : 0
        )).containsExactly(1, 1, 1);
    }

    @Test
    void consumeOrderBudget_fullBudgetSpend_marksExhausted() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        LocalDateTime now = businessDate.atTime(9, 0);
        jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set granted_amount = 100,
                       available_amount = 100
                 where id = 1
                """
        );
        jdbcTemplate.update("insert into stock_order(id, client_order_id) values (105, 'order-105')");
        reserve(budgetOrder(), "order-105", businessDate, now);

        service.consumeOrderBudget(105L, new BigDecimal("100.00"), new BigDecimal("100.00"), now.plusSeconds(1));

        assertBudget("0.00", "0.00", "100.00", "EXHAUSTED");
        assertAllocation(105L, "0.00", "100.00", "0.00");
    }

    @Test
    void consumeOrderBudget_actualCostAboveReservation_spendsOnlyPurposeReservation() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        LocalDateTime now = businessDate.atTime(9, 0);
        jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set granted_amount = 100,
                       available_amount = 100
                 where id = 1
                """
        );
        jdbcTemplate.update("insert into stock_order(id, client_order_id) values (106, 'order-106')");
        reserve(budgetOrder(), "order-106", businessDate, now);

        service.consumeOrderBudget(106L, new BigDecimal("100.00"), new BigDecimal("110.00"), now.plusSeconds(1));

        assertBudget("0.00", "0.00", "100.00", "EXHAUSTED");
        assertAllocation(106L, "0.00", "100.00", "0.00");
    }

    @Test
    void validateReconciliationChunk_crossTableReservationMismatch_failsClosed() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        AutoMarketPlannedOrder order = budgetOrder();
        jdbcTemplate.update("insert into stock_order(id, client_order_id) values (104, 'order-104')");
        reserve(order, "order-104", businessDate, businessDate.atTime(9, 0));
        jdbcTemplate.update(
                "update stock_auto_participant_funding_budget set available_amount = 500, reserved_amount = 0 where id = 1"
        );

        assertThatThrownBy(() -> service.validateReconciliationChunk(0L, 500))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field=reserved")
                .hasMessageContaining("budgetId=1");
    }

    @Test
    void validateReconciliationChunk_aboveBoundedLimit_rejectsBeforeQuery() {
        assertThatThrownBy(() -> service.validateReconciliationChunk(0L, 1_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 1000");
    }

    @Test
    void expireUnusedBudgetChunk_usesDurableKeysetCheckpoint() {
        LocalDateTime now = LocalDateTime.of(2027, 2, 2, 0, 5);

        AutoParticipantFundingBudgetService.FundingBudgetExpiryChunk first =
                service.expireUnusedBudgetChunk(now.toLocalDate(), now, 0L, 1);
        AutoParticipantFundingBudgetService.FundingBudgetExpiryChunk second =
                service.expireUnusedBudgetChunk(now.toLocalDate(), now, first.lastBudgetId(), 1);

        assertThat(first).isEqualTo(new AutoParticipantFundingBudgetService.FundingBudgetExpiryChunk(
                1, 1L, false
        ));
        assertThat(second).isEqualTo(new AutoParticipantFundingBudgetService.FundingBudgetExpiryChunk(
                0, 1L, true
        ));
    }

    @Test
    void releaseReservedBudget_afterExpiry_marksExpired() {
        LocalDate businessDate = LocalDate.of(2027, 1, 18);
        AutoMarketPlannedOrder order = budgetOrder();
        jdbcTemplate.update("insert into stock_order(id, client_order_id) values (103, 'order-103')");
        reserve(order, "order-103", businessDate, businessDate.atTime(9, 0));

        LocalDateTime releasedAt = LocalDateTime.of(2027, 2, 2, 0, 5);
        service.releaseCancelledOrderBudgets(List.of(103L), releasedAt);

        assertBudget("500.00", "0.00", "0.00", "EXPIRED");
        assertAllocation(103L, "0.00", "0.00", "100.00");
    }

    @Test
    void releaseCancelledOrderBudgets_overBoundedChunk_rejectsBeforeLocking() {
        List<Long> orderIds = LongStream.rangeClosed(1, 5_001).boxed().toList();

        assertThatThrownBy(() -> service.releaseCancelledOrderBudgets(
                orderIds,
                LocalDateTime.of(2027, 1, 18, 9, 0)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 5000");
    }

    private AutoMarketPlannedOrder budgetOrder() {
        return new AutoMarketPlannedOrder(
                10L,
                "STOCK001",
                "BUY",
                new BigDecimal("10.00"),
                10L,
                AutoParticipantFundingBudgetType.PAYDAY
        );
    }

    private void reserve(
            AutoMarketPlannedOrder order,
            String clientOrderId,
            LocalDate businessDate,
            LocalDateTime reservedAt
    ) {
        AutoParticipantFundingBudgetService.ReservationPlan plan = service.planReservations(
                List.of(order),
                businessDate
        );
        assertThat(plan.accepts(order)).isTrue();
        Map<AutoMarketPlannedOrder, String> clientOrderIds = new IdentityHashMap<>();
        clientOrderIds.put(order, clientOrderId);
        Set<AutoMarketPlannedOrder> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
        accepted.add(order);
        service.reserve(plan, clientOrderIds, accepted, reservedAt);
    }

    private void assertBudget(String available, String reserved, String spent, String status) {
        jdbcTemplate.queryForObject(
                "select * from stock_auto_participant_funding_budget where id = 1",
                (rs, rowNum) -> {
                    assertThat(rs.getBigDecimal("available_amount")).isEqualByComparingTo(available);
                    assertThat(rs.getBigDecimal("reserved_amount")).isEqualByComparingTo(reserved);
                    assertThat(rs.getBigDecimal("spent_amount")).isEqualByComparingTo(spent);
                    assertThat(rs.getString("status")).isEqualTo(status);
                    return 1;
                }
        );
    }

    private void assertAllocation(long orderId, String remaining, String spent, String released) {
        jdbcTemplate.queryForObject(
                "select * from stock_auto_participant_order_budget where order_id = ?",
                (rs, rowNum) -> {
                    assertThat(rs.getBigDecimal("remaining_reserved_amount")).isEqualByComparingTo(remaining);
                    assertThat(rs.getBigDecimal("spent_amount")).isEqualByComparingTo(spent);
                    assertThat(rs.getBigDecimal("released_amount")).isEqualByComparingTo(released);
                    return 1;
                },
                orderId
        );
    }
}
