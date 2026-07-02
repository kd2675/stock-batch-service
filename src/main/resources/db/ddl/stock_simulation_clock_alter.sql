USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_simulation_clock (
  clock_id VARCHAR(40) NOT NULL,
  base_simulation_date DATE NOT NULL,
  real_seconds_per_simulation_day INT NOT NULL,
  accumulated_real_seconds BIGINT NOT NULL DEFAULT 0,
  running BOOLEAN NOT NULL DEFAULT FALSE,
  last_started_at DATETIME NULL,
  last_heartbeat_at DATETIME NULL,
  timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Seoul',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (clock_id),
  CONSTRAINT chk_stock_simulation_clock_id CHECK (clock_id <> ''),
  CONSTRAINT chk_stock_simulation_clock_day_seconds CHECK (real_seconds_per_simulation_day > 0),
  CONSTRAINT chk_stock_simulation_clock_accumulated CHECK (accumulated_real_seconds >= 0),
  CONSTRAINT chk_stock_simulation_clock_running_dates CHECK (
    running = FALSE OR (last_started_at IS NOT NULL AND last_heartbeat_at IS NOT NULL)
  )
);
