USE STOCK_SERVICE;

ALTER TABLE stock_holding
  ADD INDEX idx_stock_holding_empty_cleanup (quantity, reserved_quantity, updated_at);
