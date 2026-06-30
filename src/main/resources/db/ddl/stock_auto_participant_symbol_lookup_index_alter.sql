USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant_symbol_config
  ADD INDEX idx_stock_auto_participant_symbol_lookup (symbol, user_key);
