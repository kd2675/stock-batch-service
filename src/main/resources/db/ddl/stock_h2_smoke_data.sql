INSERT INTO stock_instrument(symbol, name, market, enabled, created_at)
VALUES ('005930', '삼성전자', 'KOSPI', TRUE, CURRENT_TIMESTAMP);

INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)
VALUES ('005930', 70000.00, 70000.00, CURRENT_TIMESTAMP, 'smoke-seed');

INSERT INTO stock_account(user_key, cash_balance, initial_cash, created_at, updated_at)
VALUES ('h2-batch-smoke-user', 900000.00, 1000000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_account(user_key, cash_balance, initial_cash, created_at, updated_at)
VALUES ('h2-batch-smoke-seller', 100000.00, 1000000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_holding(user_key, symbol, quantity, reserved_quantity, average_price, updated_at)
VALUES ('h2-batch-smoke-seller', '005930', 1, 1, 60000.00, CURRENT_TIMESTAMP);

INSERT INTO stock_order(
  client_order_id, user_key, symbol, side, order_type, status, limit_price,
  quantity, filled_quantity, reserved_cash, created_at, updated_at
)
VALUES (
  'h2-smoke-buy-order', 'h2-batch-smoke-user', '005930', 'BUY', 'LIMIT', 'PENDING', 100000.00,
  1, 0, 100000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO stock_order(
  client_order_id, user_key, symbol, side, order_type, status, limit_price,
  quantity, filled_quantity, reserved_cash, created_at, updated_at
)
VALUES (
  'h2-smoke-sell-order', 'h2-batch-smoke-seller', '005930', 'SELL', 'LIMIT', 'PENDING', 71000.00,
  1, 0, 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
