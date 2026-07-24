USE STOCK_SERVICE;

-- Freeze the auto-participant profile with the close-cycle cohort. Historical rows are
-- intentionally left NULL because a participant's current profile cannot prove its past profile.
SET @close_account_profile_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_close_account_snapshot'
           AND column_name = 'participant_profile_type'
    ),
    'SELECT 1',
    'ALTER TABLE stock_close_account_snapshot ADD COLUMN participant_profile_type VARCHAR(40) NULL AFTER participant_category, ALGORITHM=INSTANT'
);
PREPARE close_account_profile_column_stmt FROM @close_account_profile_column_sql;
EXECUTE close_account_profile_column_stmt;
DEALLOCATE PREPARE close_account_profile_column_stmt;

SET @close_account_profile_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_close_account_snapshot'
           AND constraint_name = 'chk_stock_close_account_snapshot_profile_type'
    ),
    'SELECT 1',
    'ALTER TABLE stock_close_account_snapshot ADD CONSTRAINT chk_stock_close_account_snapshot_profile_type CHECK (participant_profile_type IS NULL OR participant_profile_type IN (''NEWS_REACTIVE'', ''MOMENTUM_FOLLOWER'', ''CONTRARIAN'', ''LOSS_AVERSE'', ''OVERCONFIDENT'', ''HERD_FOLLOWER'', ''MARKET_MAKER'', ''NOISE_TRADER'', ''VALUE_ANCHOR'', ''SCALPER'', ''DAY_TRADER'', ''SWING_TRADER'', ''LONG_TERM_HOLDER'', ''PAYDAY_ACCUMULATOR'', ''DIVIDEND_REINVESTOR'', ''LIMIT_DOWN_TRAPPED'', ''AVERAGE_DOWN_BUYER'', ''STOP_LOSS_TRADER'', ''FOMO_BUYER'', ''PANIC_SELLER'', ''DIP_BUYER'', ''PROFIT_LOCKER'', ''LIQUIDITY_AVOIDANT'', ''CASH_DEFENSIVE'', ''WHALE'', ''SMALL_DIVERSIFIER'', ''OBSERVER''))'
);
PREPARE close_account_profile_check_stmt FROM @close_account_profile_check_sql;
EXECUTE close_account_profile_check_stmt;
DEALLOCATE PREPARE close_account_profile_check_stmt;
