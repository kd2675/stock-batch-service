package stock.batch.service.batch.settlement.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.common.config.StockRuntimeIdentity;

class PortfolioSnapshotWriterMysqlTest {

    @Test
    void write_mysqlCommands_usesOneExplicitMultiRowUpsert() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        StockRuntimeIdentity runtimeIdentity = mock(StockRuntimeIdentity.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(runtimeIdentity.buildVersion()).thenReturn("test-sha");
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ConnectionCallback<Object> callback = invocation.getArgument(0, ConnectionCallback.class);
            return callback.doInConnection(connection);
        });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);
        PortfolioSnapshotWriter writer = new PortfolioSnapshotWriter(jdbcTemplate, runtimeIdentity);
        List<PortfolioSnapshotCommand> commands = List.of(command(1L), command(2L));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parametersCaptor = ArgumentCaptor.forClass(Object[].class);

        writer.write(
                commands,
                LocalDate.of(2026, 7, 15),
                LocalDateTime.of(2026, 7, 15, 18, 10)
        );

        verify(jdbcTemplate).update(sqlCaptor.capture(), parametersCaptor.capture());
        String outcome = sqlCaptor.getValue().contains("),(")
                + ":" + sqlCaptor.getValue().contains("as incoming")
                + ":" + sqlCaptor.getValue().contains("values(close_cycle_id)")
                + ":" + parametersCaptor.getValue().length;
        assertThat(outcome).isEqualTo("true:true:false:34");
    }

    private PortfolioSnapshotCommand command(long accountId) {
        return new PortfolioSnapshotCommand(
                10L,
                11L,
                accountId,
                "user-" + accountId,
                new BigDecimal("1000.00"),
                new BigDecimal("100.00"),
                new BigDecimal("2000.00"),
                10L,
                1L,
                1L,
                new BigDecimal("3100.00"),
                new BigDecimal("0.10"),
                "hash-" + accountId,
                "v1",
                "VALID"
        );
    }
}
