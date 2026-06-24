INSERT INTO stock_instrument(symbol, name, market, enabled, created_at)
VALUES ('005930', '삼성전자', 'KOSPI', TRUE, CURRENT_TIMESTAMP);

INSERT INTO stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, tick_size, price_limit_rate, enabled, created_at, updated_at)
VALUES ('005930', '삼성전자 주문장', 'ORDERBOOK', 70000.00, 100000, 100000, 1.00, 30.00, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_virtual_market_config(symbol, enabled, market_status, updated_at)
VALUES ('005930', TRUE, 'OPEN', CURRENT_TIMESTAMP);

INSERT INTO stock_order_book_market_config(symbol, enabled, market_status, updated_at)
VALUES ('005930', TRUE, 'OPEN', CURRENT_TIMESTAMP);

INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)
VALUES ('005930', 70000.00, 70000.00, CURRENT_TIMESTAMP, 'smoke-seed');

INSERT INTO stock_account(user_key, cash_balance, created_at, updated_at)
VALUES ('h2-batch-smoke-user', 900000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
SELECT id, 'DEPOSIT', 1000000.00, 'OPENING_GRANT', 'SYSTEM', CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key = 'h2-batch-smoke-user';

INSERT INTO stock_order(
  client_order_id, account_id, symbol, side, order_type, status, limit_price,
  quantity, filled_quantity, reserved_cash, created_at, updated_at
)
SELECT
  'h2-smoke-buy-order', id, '005930', 'BUY', 'LIMIT', 'PENDING', 100000.00,
  1, 0, 100000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key = 'h2-batch-smoke-user';

INSERT INTO stock_account(user_key, cash_balance, created_at, updated_at)
VALUES ('h2-batch-orderbook-buyer', 900000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_account(user_key, cash_balance, created_at, updated_at)
VALUES ('h2-batch-orderbook-seller', 100000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
SELECT id, 'DEPOSIT', 1000000.00, 'OPENING_GRANT', 'SYSTEM', CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key IN ('h2-batch-orderbook-buyer', 'h2-batch-orderbook-seller');

INSERT INTO stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
SELECT id, '005930', 1, 1, 60000.00, CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key = 'h2-batch-orderbook-seller';

INSERT INTO stock_order(
  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
  quantity, filled_quantity, reserved_cash, created_at, updated_at
)
SELECT
  'h2-smoke-orderbook-buy-order', id, '005930', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 100000.00,
  1, 0, 100000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key = 'h2-batch-orderbook-buyer';

INSERT INTO stock_order(
  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
  quantity, filled_quantity, reserved_cash, created_at, updated_at
)
SELECT
  'h2-smoke-orderbook-sell-order', id, '005930', 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING', 71000.00,
  1, 0, 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key = 'h2-batch-orderbook-seller';
