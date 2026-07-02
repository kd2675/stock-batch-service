package stock.batch.service.batch.settlement.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import stock.batch.service.batch.settlement.model.AccountSettlementTarget;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountSettlementTargetReaderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AccountSettlementTargetReader reader;

    @BeforeEach
    void setUp() {
        reader = new AccountSettlementTargetReader(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readTargets_readsSettlementAmountsWithSingleAggregateQuery() {
        doAnswer(invocation -> {
            RowMapper<AccountSettlementTarget> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
            when(resultSet.getLong("id")).thenReturn(10L);
            when(resultSet.getString("user_key")).thenReturn("user-a");
            when(resultSet.getBigDecimal("cash_balance")).thenReturn(new BigDecimal("1000000.00"));
            when(resultSet.getBigDecimal("net_cash_flow")).thenReturn(new BigDecimal("900000.00"));
            when(resultSet.getBigDecimal("market_value")).thenReturn(new BigDecimal("250000.00"));
            when(resultSet.getBigDecimal("reserved_buy_cash")).thenReturn(new BigDecimal("30000.00"));
            return List.of(rowMapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        List<AccountSettlementTarget> targets = reader.readTargets();

        assertThat(targets).hasSize(1);
        AccountSettlementTarget target = targets.getFirst();
        assertThat(target.accountId()).isEqualTo(10L);
        assertThat(target.userKey()).isEqualTo("user-a");
        assertThat(target.cashBalance()).isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(target.netCashFlow()).isEqualByComparingTo(new BigDecimal("900000.00"));
        assertThat(target.marketValue()).isEqualByComparingTo(new BigDecimal("250000.00"));
        assertThat(target.reservedBuyCash()).isEqualByComparingTo(new BigDecimal("30000.00"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getValue())
                .contains("left join (")
                .contains("from stock_account_cash_flow f")
                .contains("from stock_holding h")
                .contains("from stock_order o")
                .contains("order by a.id asc");
        verifyNoMoreInteractions(jdbcTemplate);
    }
}
