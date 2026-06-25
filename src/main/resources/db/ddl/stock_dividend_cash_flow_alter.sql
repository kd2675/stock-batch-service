USE STOCK_SERVICE;

ALTER TABLE stock_account_cash_flow
  DROP CHECK chk_stock_account_cash_flow_reason;

ALTER TABLE stock_account_cash_flow
  ADD CONSTRAINT chk_stock_account_cash_flow_reason CHECK (
    CASE `reason`
      WHEN 'OPENING_GRANT' THEN 1
      WHEN 'ADMIN_DEPOSIT' THEN 1
      WHEN 'ADMIN_WITHDRAW' THEN 1
      WHEN 'DIVIDEND_PAYMENT' THEN 1
      WHEN 'AUTO_PROFILE_RECURRING_DEPOSIT' THEN 1
      WHEN 'AUTO_PARTICIPANT_RECURRING_DEPOSIT' THEN 1
      ELSE 0
    END = 1
  );
