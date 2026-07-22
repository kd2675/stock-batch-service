package stock.batch.service.common.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        name = "stock.batch.schema-readiness.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class StockSchemaReadinessValidator implements ApplicationRunner {

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = requiredColumns();
    private static final Map<String, Set<String>> REQUIRED_NOT_NULL_COLUMNS = Map.of(
            "stock_account", Set.of("participant_category"),
            "portfolio_snapshot", Set.of("pending_subscription_asset"),
            "stock_corporate_action_entitlement", Set.of("forfeited_share_quantity"),
            "stock_close_open_order_snapshot", Set.of("source_order_status")
    );
    private static final Map<String, Set<String>> REQUIRED_CHECK_TOKENS = Map.of(
            "chk_stock_account_participant_category", Set.of("manual_participant", "auto_participant", "listing_underwriter"),
            "chk_stock_corporate_action_paid_date_order", Set.of("record_date"),
            "chk_stock_corporate_action_paid_schedule_required", Set.of("record_date"),
            "chk_stock_corporate_action_entitlement_status", Set.of("partially_subscribed"),
            "chk_stock_corporate_action_entitlement_finalized_share_limit", Set.of("forfeited_share_quantity")
    );
    private static final Map<String, Set<String>> REQUIRED_INDEXES = Map.of(
            "stock_account_cash_flow",
            Set.of(
                    "idx_stock_account_cash_flow_account_id",
                    "idx_stock_account_cash_flow_corporate_action"
            ),
            "stock_auto_participant_cash_flow_run",
            Set.of("idx_stock_auto_participant_cash_flow_run_completed"),
            "stock_corporate_action_entitlement",
            Set.of(
                    "idx_stock_corporate_action_entitlement_action_status_id",
                    "idx_stock_corporate_action_entitlement_account_status"
            ),
            "stock_corporate_action",
            Set.of("idx_stock_corporate_action_entitlement_close"),
            "stock_close_account_snapshot",
            Set.of(
                    "idx_stock_close_account_snapshot_cycle_target",
                    "idx_stock_close_account_snapshot_cycle_reconciliation"
            ),
            "stock_close_open_order_snapshot",
            Set.of(
                    "idx_stock_close_open_order_snapshot_cycle_release_order",
                    "idx_stock_close_open_order_snapshot_cycle_stream"
            ),
            "stock_batch_job_signal",
            Set.of("idx_stock_batch_job_signal_cycle_id"),
            "stock_order",
            Set.of("idx_stock_order_market_status_symbol"),
            "stock_post_close_cycle",
            Set.of(
                    "idx_stock_post_close_cycle_scope_date_status",
                    "idx_stock_post_close_cycle_scope_status_date"
            ),
            "stock_post_close_phase_attempt",
            Set.of("idx_stock_post_close_phase_attempt_cycle_id")
    );

    private final DataSource dataSource;
    private final StockRuntimeIdentity runtimeIdentity;

    public StockSchemaReadinessValidator(
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE) DataSource dataSource,
            StockRuntimeIdentity runtimeIdentity
    ) {
        this.dataSource = dataSource;
        this.runtimeIdentity = runtimeIdentity;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_COLUMNS.entrySet()) {
                Set<String> actualColumns = readColumns(metadata, catalog, requirement.getKey());
                if (actualColumns.isEmpty()) {
                    missing.add(requirement.getKey() + " table");
                    continue;
                }
                for (String requiredColumn : requirement.getValue()) {
                    if (!actualColumns.contains(requiredColumn)) {
                        missing.add(requirement.getKey() + "." + requiredColumn + " column");
                    }
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_INDEXES.entrySet()) {
                Set<String> actualIndexes = readIndexes(metadata, catalog, requirement.getKey());
                for (String requiredIndex : requirement.getValue()) {
                    if (!actualIndexes.contains(requiredIndex)) {
                        missing.add(requirement.getKey() + "." + requiredIndex + " index");
                    }
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_NOT_NULL_COLUMNS.entrySet()) {
                for (String requiredColumn : requirement.getValue()) {
                    readColumnNullable(metadata, catalog, requirement.getKey(), requiredColumn)
                            .filter(Boolean::booleanValue)
                            .ifPresent(nullable -> missing.add(
                                    requirement.getKey() + "." + requiredColumn + " NOT NULL constraint"
                            ));
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_CHECK_TOKENS.entrySet()) {
                Optional<String> checkClause = readCheckClause(connection, requirement.getKey());
                if (checkClause.isEmpty()) {
                    missing.add(requirement.getKey() + " CHECK constraint");
                    continue;
                }
                String normalizedClause = normalize(checkClause.get());
                for (String requiredToken : requirement.getValue()) {
                    if (!normalizedClause.contains(normalize(requiredToken))) {
                        missing.add(requirement.getKey() + " CHECK token " + requiredToken);
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Stock EOD schema is not ready; apply the canonical stock-back-service DDL migrations before restart: "
                            + String.join(", ", missing)
            );
        }
        log.info(
                "Stock EOD schema readiness passed: buildVersion={}, schemaVersion={}",
                runtimeIdentity.buildVersion(),
                runtimeIdentity.schemaVersion()
        );
    }

    private Set<String> readColumns(
            DatabaseMetaData metadata,
            String catalog,
            String tableName
    ) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet rows = metadata.getColumns(catalog, null, tableName, null)) {
            while (rows.next()) {
                columns.add(normalize(rows.getString("COLUMN_NAME")));
            }
        }
        if (!columns.isEmpty()) {
            return columns;
        }
        try (ResultSet rows = metadata.getColumns(
                catalog,
                null,
                tableName.toUpperCase(Locale.ROOT),
                null
        )) {
            while (rows.next()) {
                columns.add(normalize(rows.getString("COLUMN_NAME")));
            }
        }
        return columns;
    }

    private Set<String> readIndexes(
            DatabaseMetaData metadata,
            String catalog,
            String tableName
    ) throws SQLException {
        Set<String> indexes = new LinkedHashSet<>();
        readIndexes(metadata, catalog, tableName, indexes);
        if (indexes.isEmpty()) {
            readIndexes(metadata, catalog, tableName.toUpperCase(Locale.ROOT), indexes);
        }
        return indexes;
    }

    private Optional<Boolean> readColumnNullable(
            DatabaseMetaData metadata,
            String catalog,
            String tableName,
            String columnName
    ) throws SQLException {
        Optional<Boolean> nullable = readColumnNullableExact(metadata, catalog, tableName, columnName);
        if (nullable.isPresent()) {
            return nullable;
        }
        return readColumnNullableExact(
                metadata,
                catalog,
                tableName.toUpperCase(Locale.ROOT),
                columnName.toUpperCase(Locale.ROOT)
        );
    }

    private Optional<Boolean> readColumnNullableExact(
            DatabaseMetaData metadata,
            String catalog,
            String tableName,
            String columnName
    ) throws SQLException {
        try (ResultSet rows = metadata.getColumns(catalog, null, tableName, columnName)) {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.of(rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
        }
    }

    private void readIndexes(
            DatabaseMetaData metadata,
            String catalog,
            String tableName,
            Set<String> indexes
    ) throws SQLException {
        try (ResultSet rows = metadata.getIndexInfo(catalog, null, tableName, false, false)) {
            while (rows.next()) {
                String indexName = rows.getString("INDEX_NAME");
                if (indexName != null) {
                    indexes.add(normalize(indexName));
                }
            }
        }
    }

    private Optional<String> readCheckClause(Connection connection, String constraintName) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        String normalizedProductName = normalize(productName);
        boolean mysql = normalizedProductName.contains("mysql");
        boolean h2 = normalizedProductName.contains("h2");
        String sql = mysql
                ? """
                  select check_clause
                   from information_schema.check_constraints
                   where constraint_schema = database()
                     and lower(constraint_name) = lower(?)
                  """
                : h2
                ? """
                  select "CHECK_CLAUSE"
                    from "INFORMATION_SCHEMA"."CHECK_CONSTRAINTS"
                   where lower("CONSTRAINT_SCHEMA") = lower(current_schema)
                     and lower("CONSTRAINT_NAME") = lower(?)
                  """
                : """
                  select check_clause
                   from information_schema.check_constraints
                   where constraint_schema = current_schema
                     and lower(constraint_name) = lower(?)
                  """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, constraintName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(rows.getString("check_clause"));
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> requiredColumns() {
        Map<String, Set<String>> requirements = new LinkedHashMap<>();
        requirements.put("stock_account", Set.of("participant_category"));
        requirements.put("stock_market_business_state", Set.of(
                "state_id", "active_business_date", "preparing_business_date", "raw_simulation_date", "version"
        ));
        requirements.put("stock_market_session_fence", Set.of(
                "market_type", "symbol", "business_date", "session_epoch", "session_state", "version"
        ));
        requirements.put("stock_auto_participant_cash_flow_run", Set.of(
                "run_key", "operation", "last_account_id", "processed_count",
                "completed_at", "created_at", "updated_at"
        ));
        requirements.put("stock_post_close_cycle", Set.of(
                "id", "business_date", "scope_type", "scope_key", "cycle_kind", "phase", "status",
                "phase_revision", "version", "owner_id", "lease_until", "next_retry_at", "close_run_id",
                "settlement_eligible_at", "attempt_count", "build_version", "schema_version"
        ));
        requirements.put("stock_post_close_phase_attempt", Set.of(
                "cycle_id", "phase", "attempt_no", "batch_job_execution_id", "owner_id", "status",
                "error_code", "error_message", "build_version", "schema_version"
        ));
        requirements.put("stock_post_close_readiness_check", Set.of(
                "close_cycle_id", "check_code", "display_order", "check_status",
                "failure_count", "message", "checked_at"
        ));
        requirements.put("stock_post_close_cycle_metric", Set.of(
                "close_cycle_id", "close_run_id", "captured_open_order_count", "cancelled_order_count",
                "released_buy_cash", "released_sell_quantity",
                "settlement_target_account_count", "account_snapshot_count", "holding_snapshot_count",
                "price_snapshot_count", "open_order_summary_count", "reconciliation_mismatch_count",
                "settled_account_count", "settlement_missing_account_count"
        ));
        requirements.put("stock_holding_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "account_id", "symbol", "quantity", "reserved_quantity",
                "average_price", "evaluation_price", "snapshot_at"
        ));
        requirements.put("stock_close_account_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "account_id", "user_key", "account_status",
                "participant_category",
                "settlement_target", "pre_cancel_cash", "pre_cancel_order_reserved_cash",
                "subscription_reserved_cash", "post_cancel_cash", "external_net_cash_flow",
                "cash_flow_watermark_id", "holding_market_value", "holding_quantity",
                "reserved_sell_quantity", "holding_position_count", "reconciliation_status", "snapshot_at"
        ));
        requirements.put("stock_close_price_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "symbol", "close_price", "previous_close", "price_time",
                "price_provider", "last_execution_id", "order_book_symbol", "snapshot_at"
        ));
        requirements.put("stock_close_open_order_summary", Set.of(
                "close_cycle_id", "close_run_id", "symbol", "pre_cancel_open_order_count",
                "pre_cancel_buy_order_count", "pre_cancel_sell_order_count",
                "pre_cancel_remaining_buy_quantity", "pre_cancel_remaining_sell_quantity",
                "pre_cancel_reserved_buy_cash", "pre_cancel_reserved_sell_quantity",
                "post_cancel_open_order_count", "reconciliation_status", "snapshot_at"
        ));
        requirements.put("stock_close_open_order_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "order_id", "account_id", "symbol", "side",
                "source_order_status", "remaining_quantity", "reserved_cash", "captured_at", "released_at"
        ));
        requirements.put("stock_corporate_action_processing", Set.of(
                "action_id", "account_scope_key", "action_phase", "effective_business_date",
                "status", "attempt_count", "processed_count", "amount", "quantity"
        ));
        requirements.put("stock_corporate_action", Set.of(
                "record_date", "entitlement_close_cycle_id", "entitlement_close_run_id"
        ));
        requirements.put("stock_corporate_action_entitlement", Set.of(
                "subscribed_share_quantity", "subscribed_cash_amount", "forfeited_share_quantity", "status"
        ));
        requirements.put("stock_account_cash_flow", Set.of(
                "corporate_action_id", "corporate_action_entitlement_id", "effective_business_date"
        ));
        requirements.put("stock_batch_job_signal", Set.of(
                "status", "requested_business_date", "requested_session_epoch", "expected_cycle_id",
                "eligible_at", "next_attempt_at", "attempt_count", "max_attempts", "claim_token",
                "lease_until", "failure_class"
        ));
        requirements.put("portfolio_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "pending_subscription_asset",
                "holding_quantity", "reserved_sell_quantity",
                "holding_position_count", "input_hash", "calculation_version", "data_quality_status",
                "source_build_version"
        ));
        requirements.put("stock_execution_daily_account_snapshot", Set.of(
                "close_run_id", "account_id", "execution_amount", "last_executed_at"
        ));
        requirements.put("stock_execution_account_day_summary", Set.of(
                "simulation_trade_date", "account_id", "execution_count", "buy_quantity", "sell_quantity",
                "gross_amount", "buy_gross_amount", "sell_gross_amount", "buy_net_amount", "sell_net_amount",
                "fee_amount", "tax_amount", "realized_profit", "last_executed_at", "updated_at"
        ));
        return Map.copyOf(requirements);
    }
}
