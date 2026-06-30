package stock.batch.service.batch.automarket.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoMarketReaderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AutoMarketReader reader;

    @BeforeEach
    void setUp() {
        reader = new AutoMarketReader(jdbcTemplate);
    }

    @Test
    void findExistingAccountUserKeys_emptyInput_skipsQuery() {
        List<String> userKeys = reader.findExistingAccountUserKeys(List.of());

        assertThat(userKeys).isEmpty();
        verify(jdbcTemplate, never()).queryForList(any(String.class), eq(String.class), any());
    }

    @Test
    void findExistingAccountUserKeys_readsAllKeysWithSingleInQuery() {
        when(jdbcTemplate.queryForList(
                any(String.class),
                eq(String.class),
                eq("stock-auto-001"),
                eq("stock-auto-002")
        )).thenReturn(List.of("stock-auto-001"));

        List<String> userKeys = reader.findExistingAccountUserKeys(List.of("stock-auto-001", "stock-auto-002"));

        assertThat(userKeys).containsExactly("stock-auto-001");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(
                sqlCaptor.capture(),
                eq(String.class),
                eq("stock-auto-001"),
                eq("stock-auto-002")
        );
        assertThat(sqlCaptor.getValue())
                .isEqualTo("select user_key from stock_account where user_key in (?, ?)");
    }

    @Test
    void findRecentCashFlows_readsMultipleAccountsAndReasonsWithSingleQuery() {
        LocalDateTime since = LocalDateTime.of(2026, 6, 29, 9, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 29, 9, 30);
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<AutoParticipantRecentCashFlow>>any(),
                eq(10L),
                eq(20L),
                eq("AUTO_PARTICIPANT_RECURRING_DEPOSIT"),
                eq("AUTO_PROFILE_RECURRING_DEPOSIT"),
                eq("AUTO_MARKET"),
                eq(since)
        )).thenAnswer(invocation -> {
            RowMapper<AutoParticipantRecentCashFlow> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("account_id")).thenReturn(10L);
            when(resultSet.getString("reason")).thenReturn("AUTO_PROFILE_RECURRING_DEPOSIT");
            when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(createdAt));
            return List.of(rowMapper.mapRow(resultSet, 0));
        });

        List<AutoParticipantRecentCashFlow> cashFlows = reader.findRecentCashFlows(
                List.of(10L, 20L),
                Set.of("AUTO_PROFILE_RECURRING_DEPOSIT", "AUTO_PARTICIPANT_RECURRING_DEPOSIT"),
                "AUTO_MARKET",
                since
        );

        assertThat(cashFlows).hasSize(1);
        AutoParticipantRecentCashFlow cashFlow = cashFlows.getFirst();
        assertThat(cashFlow.accountId()).isEqualTo(10L);
        assertThat(cashFlow.reason()).isEqualTo("AUTO_PROFILE_RECURRING_DEPOSIT");
        assertThat(cashFlow.createdAt()).isEqualTo(createdAt);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AutoParticipantRecentCashFlow>>any(),
                eq(10L),
                eq(20L),
                eq("AUTO_PARTICIPANT_RECURRING_DEPOSIT"),
                eq("AUTO_PROFILE_RECURRING_DEPOSIT"),
                eq("AUTO_MARKET"),
                eq(since)
        );
        assertThat(sqlCaptor.getValue())
                .contains("from stock_account_cash_flow")
                .contains("account_id in (?, ?)")
                .contains("reason in (?, ?)")
                .contains("created_by = ?")
                .contains("created_at >= ?");
    }

    @Test
    void findExpiredAutoOrders_readsCandidateOrdersWithProfileAndCreatedAtInSingleQuery() {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        LocalDateTime threshold = LocalDateTime.of(2026, 6, 29, 10, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 29, 9, 59);
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<AutoOrder>>any(),
                eq("STOCK001"),
                eq(threshold),
                eq(5400)
        )).thenAnswer(invocation -> {
            RowMapper<AutoOrder> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("id")).thenReturn(100L);
            when(resultSet.getLong("account_id")).thenReturn(10L);
            when(resultSet.getString("symbol")).thenReturn("STOCK001");
            when(resultSet.getString("side")).thenReturn("BUY");
            when(resultSet.getLong("quantity")).thenReturn(3L);
            when(resultSet.getLong("filled_quantity")).thenReturn(1L);
            when(resultSet.getBigDecimal("reserved_cash")).thenReturn(new BigDecimal("140000.00"));
            when(resultSet.getString("profile_type")).thenReturn("SCALPER");
            when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(createdAt));
            return List.of(rowMapper.mapRow(resultSet, 0));
        });

        List<AutoOrder> orders = reader.findExpiredAutoOrders(config, threshold, 5400);

        assertThat(orders).hasSize(1);
        AutoOrder order = orders.getFirst();
        assertThat(order.id()).isEqualTo(100L);
        assertThat(order.profileType()).isEqualTo(AutoParticipantProfileType.SCALPER);
        assertThat(order.createdAt()).isEqualTo(createdAt);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AutoOrder>>any(),
                eq("STOCK001"),
                eq(threshold),
                eq(5400)
        );
        assertThat(sqlCaptor.getValue())
                .contains("join stock_auto_participant p")
                .contains("p.profile_type")
                .contains("o.created_at < ?")
                .contains("limit ?")
                .doesNotContain("p.profile_type = ?");
    }

    @Test
    void findEnabledParticipantStrategiesBySymbol_readsActiveParticipantsAndSymbolOverridesOnce() {
        AutoMarketConfig stock001 = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoMarketConfig stock002 = new AutoMarketConfig(
                "STOCK002",
                8,
                100,
                15,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("30000.00"),
                new BigDecimal("30000.00"),
                null
        );
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any()
        )).thenAnswer(invocation -> {
            RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet first = org.mockito.Mockito.mock(ResultSet.class);
            when(first.getString("user_key")).thenReturn("auto-001");
            when(first.getLong("account_id")).thenReturn(10L);
            when(first.getString("profile_type")).thenReturn("NEWS_REACTIVE");
            when(first.getBigDecimal("recurring_cash_amount")).thenReturn(new BigDecimal("1000.00"));
            when(first.getBigDecimal("recurring_cash_interval_value")).thenReturn(new BigDecimal("1.00"));
            when(first.getString("recurring_cash_interval_unit")).thenReturn("DAY");
            ResultSet second = org.mockito.Mockito.mock(ResultSet.class);
            when(second.getString("user_key")).thenReturn("auto-002");
            when(second.getLong("account_id")).thenReturn(20L);
            when(second.getString("profile_type")).thenReturn("NOISE");
            return List.of(rowMapper.mapRow(first, 0), rowMapper.mapRow(second, 1));
        });
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                eq("STOCK001"),
                eq("STOCK002")
        )).thenAnswer(invocation -> {
            RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet enabledOverride = org.mockito.Mockito.mock(ResultSet.class);
            when(enabledOverride.getString("user_key")).thenReturn("auto-001");
            when(enabledOverride.getString("symbol")).thenReturn("STOCK001");
            when(enabledOverride.getBoolean("enabled")).thenReturn(true);
            when(enabledOverride.getInt("intensity")).thenReturn(9);
            ResultSet disabledOverride = org.mockito.Mockito.mock(ResultSet.class);
            when(disabledOverride.getString("user_key")).thenReturn("auto-002");
            when(disabledOverride.getString("symbol")).thenReturn("STOCK001");
            when(disabledOverride.getBoolean("enabled")).thenReturn(false);
            when(disabledOverride.getInt("intensity")).thenReturn(1);
            return List.of(rowMapper.mapRow(enabledOverride, 0), rowMapper.mapRow(disabledOverride, 1));
        });

        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = reader.findEnabledParticipantStrategiesBySymbol(List.of(stock001, stock002));

        assertThat(strategiesBySymbol.get("STOCK001"))
                .extracting(AutoParticipantStrategy::userKey)
                .containsExactly("auto-001");
        assertThat(strategiesBySymbol.get("STOCK001").getFirst().intensity()).isEqualTo(9);
        assertThat(strategiesBySymbol.get("STOCK002"))
                .extracting(AutoParticipantStrategy::userKey)
                .containsExactly("auto-001", "auto-002");
        assertThat(strategiesBySymbol.get("STOCK002"))
                .extracting(AutoParticipantStrategy::intensity)
                .containsExactly(8, 8);

        ArgumentCaptor<String> activeSqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                activeSqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any()
        );
        assertThat(activeSqlCaptor.getValue())
                .contains("from stock_auto_participant p")
                .contains("join stock_account a")
                .doesNotContain("stock_auto_participant_symbol_config");

        ArgumentCaptor<String> overrideSqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                overrideSqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                eq("STOCK001"),
                eq("STOCK002")
        );
        assertThat(overrideSqlCaptor.getValue())
                .contains("from stock_auto_participant_symbol_config")
                .contains("where symbol in (")
                .contains("?, ?");
    }

    @Test
    void findTradingSnapshots_readsCashHoldingAndDividendWithSingleQuery() {
        LocalDateTime since = LocalDateTime.of(2026, 6, 29, 9, 0);
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<AutoParticipantTradingSnapshot>>any(),
                eq("STOCK001"),
                eq(since),
                eq(10L),
                eq(20L)
        )).thenAnswer(invocation -> {
            RowMapper<AutoParticipantTradingSnapshot> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("account_id")).thenReturn(10L);
            when(resultSet.getBigDecimal("cash_balance")).thenReturn(new BigDecimal("100000.00"));
            when(resultSet.getLong("available_quantity")).thenReturn(7L);
            when(resultSet.getBigDecimal("average_price")).thenReturn(new BigDecimal("50000.00"));
            when(resultSet.getBigDecimal("recent_dividend_cash_amount")).thenReturn(new BigDecimal("3000.00"));
            return List.of(rowMapper.mapRow(resultSet, 0));
        });

        List<AutoParticipantTradingSnapshot> snapshots = reader.findTradingSnapshots(List.of(10L, 20L), "STOCK001", since);

        assertThat(snapshots).hasSize(1);
        AutoParticipantTradingSnapshot snapshot = snapshots.getFirst();
        assertThat(snapshot.accountId()).isEqualTo(10L);
        assertThat(snapshot.cashBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(snapshot.availableQuantity()).isEqualTo(7L);
        assertThat(snapshot.averagePrice()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(snapshot.recentDividendCashAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AutoParticipantTradingSnapshot>>any(),
                eq("STOCK001"),
                eq(since),
                eq(10L),
                eq(20L)
        );
        assertThat(sqlCaptor.getValue())
                .contains("from stock_account a")
                .contains("left join stock_holding h")
                .contains("left join stock_account_cash_flow f")
                .contains("where a.id in (?, ?)");
    }
}
