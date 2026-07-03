USE STOCK_SERVICE;

ALTER TABLE stock_order
  ADD INDEX idx_stock_order_order_book_expiry (market_type, symbol, created_at, id, status, account_id);
