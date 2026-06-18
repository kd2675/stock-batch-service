package stock.batch.service.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class StockSchemaConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from portfolio_snapshot");
        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_account");
    }

    @Test
    void stockAccount_negativeCash_isRejectedBySchema() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into stock_account(user_key, cash_balance, initial_cash, created_at, updated_at) values (?, ?, ?, ?, ?)",
                "bad-cash",
                new BigDecimal("-1.00"),
                new BigDecimal("10000000.00"),
                LocalDateTime.now(),
                LocalDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockOrder_filledQuantityGreaterThanQuantity_isRejectedBySchema() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                stockOrderInsertSql(),
                "bad-fill",
                "bad-order-user",
                "005930",
                "BUY",
                "LIMIT",
                "PENDING",
                new BigDecimal("70000.00"),
                1L,
                2L,
                new BigDecimal("70000.00"),
                LocalDateTime.now(),
                LocalDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockOrder_unknownSide_isRejectedBySchema() {
        assertThatThrownBy(() -> insertStockOrder("bad-side", "HOLD", "LIMIT", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockOrder_unknownOrderType_isRejectedBySchema() {
        assertThatThrownBy(() -> insertStockOrder("bad-order-type", "BUY", "STOP", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockOrder_unknownStatus_isRejectedBySchema() {
        assertThatThrownBy(() -> insertStockOrder("bad-status", "BUY", "LIMIT", "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockOrder_terminalStatusWithReservedCash_isRejectedBySchema() {
        assertThatThrownBy(() -> insertStockOrder("bad-terminal-reserve", "BUY", "LIMIT", "CANCELLED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockExecution_unknownSide_isRejectedBySchema() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                stockExecutionInsertSql(),
                1L,
                "bad-execution-user",
                "005930",
                "HOLD",
                1L,
                new BigDecimal("70000.00"),
                "VIRTUAL_MARKET_PRICE",
                LocalDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockExecution_unknownSource_isRejectedBySchema() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                stockExecutionInsertSql(),
                1L,
                "bad-execution-source-user",
                "005930",
                "BUY",
                1L,
                new BigDecimal("70000.00"),
                "MANUAL",
                LocalDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockHolding_reservedQuantityGreaterThanQuantity_isRejectedBySchema() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into stock_holding(user_key, symbol, quantity, reserved_quantity, average_price, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                "bad-holding-user",
                "005930",
                1L,
                2L,
                new BigDecimal("70000.00"),
                LocalDateTime.now()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockOrder_orderBookMatchingIndexes_existInSchema() throws SQLException {
        assertIndexNames("stock_order")
                .contains(
                        "idx_stock_order_execution_scan",
                        "idx_stock_order_order_book_match",
                        "idx_stock_order_user_status_created"
                );
    }

    @Test
    void stockPriceTick_symbolTimeIndex_existsInSchema() throws SQLException {
        assertIndexNames("stock_price_tick")
                .contains("idx_stock_price_tick_symbol_time");
    }

    private org.assertj.core.api.ListAssert<String> assertIndexNames(String tableName) throws SQLException {
        var indexNames = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
             var indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                if (indexName != null) {
                    indexNames.add(indexName.toLowerCase(Locale.ROOT));
                }
            }
        }
        return assertThat(indexNames);
    }

    private void insertStockOrder(String clientOrderId, String side, String orderType, String status) {
        jdbcTemplate.update(
                stockOrderInsertSql(),
                clientOrderId,
                "bad-order-user",
                "005930",
                side,
                orderType,
                status,
                new BigDecimal("70000.00"),
                1L,
                0L,
                new BigDecimal("70000.00"),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private String stockOrderInsertSql() {
        return """
                insert into stock_order(
                  client_order_id, user_key, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private String stockExecutionInsertSql() {
        return """
                insert into stock_execution(
                  order_id, user_key, symbol, side, quantity, price, source, executed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }
}
