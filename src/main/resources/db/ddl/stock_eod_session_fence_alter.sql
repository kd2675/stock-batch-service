USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_market_business_state (
  state_id VARCHAR(20) NOT NULL,
  active_business_date DATE NOT NULL,
  preparing_business_date DATE NULL,
  raw_simulation_date DATE NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (state_id),
  CONSTRAINT chk_stock_market_business_state_version CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS stock_market_session_fence (
  market_type VARCHAR(20) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  business_date DATE NOT NULL,
  session_epoch BIGINT NOT NULL,
  session_state VARCHAR(20) NOT NULL,
  state_changed_at DATETIME NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (market_type, symbol),
  KEY idx_stock_market_session_fence_state (business_date, session_state, market_type, symbol),
  CONSTRAINT chk_stock_market_session_fence_market_type CHECK (
    CASE `market_type` WHEN 'VIRTUAL_PRICE' THEN 1 WHEN 'ORDER_BOOK' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_session_fence_state CHECK (
    CASE `session_state` WHEN 'OPEN' THEN 1 WHEN 'CLOSING' THEN 1 WHEN 'CLOSED' THEN 1 WHEN 'PREPARING' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_session_fence_epoch CHECK (session_epoch > 0),
  CONSTRAINT chk_stock_market_session_fence_version CHECK (version >= 0)
);

-- Run this migration only after stock-back-service and stock-batch-service have stopped.
-- A non-default market open time can be supplied on the same MySQL session before sourcing:
--   SET @stock_market_open_time = TIME('07:00:00');
-- A clock that was not stopped gracefully is frozen at its last heartbeat so migration time
-- itself never advances the simulation and never rewinds it to base_simulation_date.
SET @stock_market_open_time = COALESCE(@stock_market_open_time, TIME('06:00:00'));

INSERT INTO stock_market_business_state(
    state_id,
    active_business_date,
    preparing_business_date,
    raw_simulation_date,
    version,
    created_at,
    updated_at
)
SELECT 'DEFAULT',
       CASE
           WHEN snapshot.simulation_seconds_in_day < TIME_TO_SEC(@stock_market_open_time)
               THEN CASE
                   WHEN snapshot.raw_simulation_date = snapshot.base_simulation_date
                       THEN snapshot.base_simulation_date
                   ELSE DATE_SUB(snapshot.raw_simulation_date, INTERVAL 1 DAY)
               END
           ELSE snapshot.raw_simulation_date
       END,
       null,
       snapshot.raw_simulation_date,
       0,
       NOW(),
       NOW()
  FROM (
      SELECT clock.base_simulation_date,
             DATE_ADD(
                 clock.base_simulation_date,
                 INTERVAL FLOOR(clock.effective_elapsed_seconds / clock.real_seconds_per_simulation_day) DAY
             ) AS raw_simulation_date,
             FLOOR(
                 MOD(clock.effective_elapsed_seconds, clock.real_seconds_per_simulation_day)
                 * 86400
                 / clock.real_seconds_per_simulation_day
             ) AS simulation_seconds_in_day
        FROM (
            SELECT COALESCE(source.base_simulation_date, CURRENT_DATE) AS base_simulation_date,
                   GREATEST(COALESCE(source.real_seconds_per_simulation_day, 7200), 1)
                       AS real_seconds_per_simulation_day,
                   GREATEST(
                       COALESCE(source.accumulated_real_seconds, 0)
                       + CASE
                           WHEN source.running = true
                                AND source.last_started_at IS NOT NULL
                                AND source.last_heartbeat_at IS NOT NULL
                               THEN GREATEST(
                                   TIMESTAMPDIFF(
                                       SECOND,
                                       source.last_started_at,
                                       source.last_heartbeat_at
                                   ),
                                   0
                               )
                           ELSE 0
                       END,
                       0
                   ) AS effective_elapsed_seconds
              FROM (SELECT 1 AS seed) seed
              LEFT JOIN stock_simulation_clock source
                ON source.clock_id = 'DEFAULT'
        ) clock
  ) snapshot
 WHERE NOT EXISTS (
     SELECT 1
       FROM stock_market_business_state
      WHERE state_id = 'DEFAULT'
 );

INSERT IGNORE INTO stock_market_session_fence(
    market_type,
    symbol,
    business_date,
    session_epoch,
    session_state,
    state_changed_at,
    version,
    created_at,
    updated_at
)
SELECT 'VIRTUAL_PRICE',
       c.symbol,
       s.active_business_date,
       1,
       'CLOSED',
       NOW(),
       0,
       NOW(),
       NOW()
  FROM stock_virtual_market_config c
 CROSS JOIN stock_market_business_state s
 WHERE s.state_id = 'DEFAULT';

INSERT IGNORE INTO stock_market_session_fence(
    market_type,
    symbol,
    business_date,
    session_epoch,
    session_state,
    state_changed_at,
    version,
    created_at,
    updated_at
)
SELECT 'ORDER_BOOK',
       c.symbol,
       s.active_business_date,
       1,
       'CLOSED',
       NOW(),
       0,
       NOW(),
       NOW()
  FROM stock_order_book_market_config c
 CROSS JOIN stock_market_business_state s
 WHERE s.state_id = 'DEFAULT';
