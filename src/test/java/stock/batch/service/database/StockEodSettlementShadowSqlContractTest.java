package stock.batch.service.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class StockEodSettlementShadowSqlContractTest {

    private static final Path SHADOW_SQL = Path.of(
            "src/main/resources/db/diagnostics/stock_eod_settlement_shadow_mysql.sql"
    );
    private static final Pattern BUSINESS_WRITE = Pattern.compile(
            "(?im)^\\s*(insert|update|delete|replace|merge|alter|create|drop|truncate|lock|call)\\b"
    );

    @Test
    void settlementShadowSql_isReadOnlyClosedCycleGuardedAndBounded() throws IOException {
        String sql = Files.readString(SHADOW_SQL, StandardCharsets.UTF_8);
        String normalized = sql.toLowerCase(Locale.ROOT);

        assertThat(firstExecutableSqlLine(sql)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(BUSINESS_WRITE.matcher(sql).find()).isFalse();
        assertThat(normalized).contains(
                "start transaction with consistent snapshot, read only",
                "cycle.phase = 'portfolio_settled'",
                "cycle.status = 'pending'",
                "cycle.owner_id is null",
                "from stock_post_close_phase_attempt attempt",
                "attempt.phase = 'portfolio_settled'",
                "overnight_phase_already_attempted",
                "fence.session_state = 'open'",
                "into @stock_eod_shadow_guard_valid, @stock_eod_shadow_guard_reason",
                "@stock_eod_shadow_guard_valid = 1",
                "@stock_eod_shadow_guard_reason as guard_status",
                "account_snapshot.account_id > @stock_eod_shadow_after_account_id",
                "limit 200",
                "force index (idx_stock_account_cash_flow_account_id)",
                "force index (idx_stock_order_account_status_created)",
                "force index (idx_stock_corporate_action_entitlement_account_status)",
                "portfolio-v2-frozen-close",
                "end as shadow_status",
                "as page_mismatch_count",
                "commit;"
        );
        assertThat(countOccurrences(normalized, "from stock_order ")).isOne();
        assertThat(normalized)
                .doesNotContain("stock_execution")
                .doesNotContain("for update")
                .doesNotContain("prepare ")
                .doesNotContain("requires_closed_portfolio_settled_cycle")
                .doesNotContain(" into stock_");
    }

    private String firstExecutableSqlLine(String sql) {
        return sql.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("--"))
                .findFirst()
                .orElse("");
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
