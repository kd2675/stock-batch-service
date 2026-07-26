-- Separates withdrawn automatic-participant shares from listing-underwriter inventory.
-- The legacy underwriter_account_id remains populated for old readers during the transition.

USE STOCK_SERVICE;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_share_return ADD COLUMN receiver_account_id BIGINT NULL AFTER underwriter_account_id',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'receiver_account_id'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_share_return ADD COLUMN receiver_role VARCHAR(40) NULL AFTER receiver_account_id',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'receiver_role'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_share_return ADD COLUMN transfer_reason VARCHAR(50) NULL AFTER receiver_role',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'transfer_reason'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

UPDATE stock_auto_participant_share_return
   SET receiver_account_id = underwriter_account_id,
       receiver_role = 'LISTING_UNDERWRITER',
       transfer_reason = 'LEGACY_UNDERWRITER_RETURN'
 WHERE receiver_account_id IS NULL
    OR receiver_role IS NULL
    OR transfer_reason IS NULL;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_auto_participant_share_return MODIFY COLUMN receiver_account_id BIGINT NOT NULL',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'receiver_account_id'
       AND is_nullable = 'YES'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_auto_participant_share_return MODIFY COLUMN receiver_role VARCHAR(40) NOT NULL',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'receiver_role'
       AND is_nullable = 'YES'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_auto_participant_share_return MODIFY COLUMN transfer_reason VARCHAR(50) NOT NULL',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'transfer_reason'
       AND is_nullable = 'YES'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_share_return ADD INDEX idx_stock_auto_share_return_receiver(receiver_account_id, symbol, withdrawal_id)',
        'SELECT 1'
    )
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND index_name = 'idx_stock_auto_share_return_receiver'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_share_return ADD CONSTRAINT chk_stock_auto_share_return_receiver_role CHECK (CASE receiver_role WHEN ''LISTING_UNDERWRITER'' THEN 1 WHEN ''SYSTEM_CUSTODY'' THEN 1 ELSE 0 END = 1)',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND constraint_name = 'chk_stock_auto_share_return_receiver_role'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

SET @stock_custody_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_share_return ADD CONSTRAINT chk_stock_auto_share_return_reason CHECK (CASE transfer_reason WHEN ''LEGACY_UNDERWRITER_RETURN'' THEN 1 WHEN ''AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY'' THEN 1 ELSE 0 END = 1)',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND constraint_name = 'chk_stock_auto_share_return_reason'
);
PREPARE stock_custody_statement FROM @stock_custody_sql;
EXECUTE stock_custody_statement;
DEALLOCATE PREPARE stock_custody_statement;

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
SELECT
    'SYSTEM_CUSTODY', '시스템 보관기관', 'SYSTEM_CUSTODY', 'ACTIVE',
    'SYSTEM_CUSTODY:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_market_participant
     WHERE participant_code = 'SYSTEM_CUSTODY'
);

INSERT INTO stock_account(
    user_key, account_code, status, participant_category,
    self_trade_group_id, cash_balance, created_at, updated_at
)
SELECT
    'stock-system-custody', 'SYSTEM-CUSTODY', 'ACTIVE', 'SYSTEM_CUSTODY',
    'SYSTEM_CUSTODY:DEFAULT', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_account
     WHERE user_key = 'stock-system-custody'
);

DELETE FROM stock_market_participant_account
 WHERE account_role = 'SYSTEM_CUSTODY'
   AND desk_code = 'DEFAULT'
   AND participant_id IN (
       SELECT id
         FROM stock_market_participant
        WHERE participant_code = 'SYSTEM_CUSTODY'
   )
   AND NOT EXISTS (
       SELECT 1
         FROM stock_account
        WHERE stock_account.id = stock_market_participant_account.account_id
   );

INSERT INTO stock_market_participant_account(
    participant_id, account_id, account_role, desk_code,
    effective_from, effective_to, status, created_at, updated_at
)
SELECT
    participant.id, account.id, 'SYSTEM_CUSTODY', 'DEFAULT',
    DATE '1970-01-01', NULL, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM stock_market_participant participant
  JOIN stock_account account
    ON account.user_key = 'stock-system-custody'
 WHERE participant.participant_code = 'SYSTEM_CUSTODY'
   AND NOT EXISTS (
       SELECT 1
         FROM stock_market_participant_account existing
        WHERE existing.account_id = account.id
   );
