USE STOCK_SERVICE;

ALTER TABLE stock_account
  ADD INDEX idx_stock_account_status_id (status, id);

ALTER TABLE stock_account_cash_flow
  ADD INDEX idx_stock_account_cash_flow_time (created_at, id),
  ADD INDEX idx_stock_account_cash_flow_account_reason_creator_time (account_id, reason, created_by, created_at, id),
  ADD INDEX idx_stock_account_cash_flow_account_type_reason_time (account_id, flow_type, reason, created_at, id);

ALTER TABLE stock_order
  ADD INDEX idx_stock_order_account_symbol_created (account_id, symbol, created_at),
  ADD INDEX idx_stock_order_market_status_side (market_type, status, side),
  ADD INDEX idx_stock_order_market_status_account_time (market_type, status, account_id, created_at),
  ADD INDEX idx_stock_order_market_account_time (market_type, account_id, created_at),
  ADD INDEX idx_stock_order_market_account_symbol_time (market_type, account_id, symbol, created_at),
  ADD INDEX idx_stock_order_market_created_status (market_type, created_at, status);

ALTER TABLE stock_execution
  ADD INDEX idx_stock_execution_account_symbol_time (account_id, symbol, executed_at),
  ADD INDEX idx_stock_execution_source_account_time (source, account_id, executed_at),
  ADD INDEX idx_stock_execution_source_account_symbol_time (source, account_id, symbol, executed_at),
  ADD INDEX idx_stock_execution_source_time_account (source, executed_at, account_id),
  ADD INDEX idx_stock_execution_source_symbol_time (source, symbol, executed_at),
  ADD INDEX idx_stock_execution_source_time (source, executed_at),
  ADD INDEX idx_stock_execution_time_account (executed_at, account_id);

ALTER TABLE stock_holding
  ADD INDEX idx_stock_holding_symbol_account (symbol, account_id);

ALTER TABLE stock_auto_participant_symbol_config
  ADD INDEX idx_stock_auto_participant_symbol_lookup (symbol, user_key),
  ADD INDEX idx_stock_auto_participant_symbol_user_enabled (user_key, enabled, symbol);

ALTER TABLE stock_auto_participant
  ADD INDEX idx_stock_auto_participant_active (withdrawn_at, enabled, user_key),
  ADD INDEX idx_stock_auto_participant_profile_active (withdrawn_at, profile_type, enabled, user_key);

ALTER TABLE stock_corporate_action
  ADD INDEX idx_stock_corporate_action_status_symbol (status, symbol);
