USE STOCK_SERVICE;

-- Make instrument report rise/fall reasons optional.
-- Apply once to an existing MySQL stock schema if chk_stock_report_content_scope still requires both reason fields.

ALTER TABLE stock_instrument_report_event
  DROP CHECK chk_stock_report_content_scope;

ALTER TABLE stock_instrument_report_event
  ADD CONSTRAINT chk_stock_report_content_scope CHECK (
    (
      event_type = 'DELETE'
      AND title IS NULL
      AND summary IS NULL
      AND score IS NULL
      AND rise_reason IS NULL
      AND fall_reason IS NULL
    )
    OR (
      event_type IN ('PUBLISH', 'UPDATE')
      AND title IS NOT NULL
      AND summary IS NOT NULL
      AND score IS NOT NULL
    )
  );
