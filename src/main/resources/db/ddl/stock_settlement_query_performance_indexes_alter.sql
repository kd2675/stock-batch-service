USE STOCK_SERVICE;

ALTER TABLE stock_order
  ADD INDEX idx_stock_order_side_status_account (side, status, account_id);
