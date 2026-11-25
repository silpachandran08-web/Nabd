SET lock_timeout = '5s';
-- PHL-023: what a schedule class asks of the counter, as data (reference rows, changed by migration + review only).
--   dispense_ack_required: the sale of such a line needs the seller's second confirmation with a reason (FRD PHR-04).
--   pharmacist_warning:    selling such a line with no registered pharmacist on duty is warned about and audited, never blocked (CEO-27).
-- NEEDS REGULATORY CHECK: which classes carry each flag, and the 2-year retention of the Schedule X duplicate copy, follow the
-- story's acceptance criteria (H1: confirmation; H1 and X: pharmacist warning; X: copy kept 2 years). NDPS and the other markets'
-- controlled classes are left off until the regulatory owner decides.
ALTER TABLE pharmacy_schedule_rules
  ADD COLUMN dispense_ack_required boolean NOT NULL DEFAULT false,
  ADD COLUMN pharmacist_warning    boolean NOT NULL DEFAULT false;
UPDATE pharmacy_schedule_rules SET dispense_ack_required = true, pharmacist_warning = true WHERE market = 'IN' AND schedule_class = 'IN_H1';
UPDATE pharmacy_schedule_rules SET pharmacist_warning = true, retention_years = 2 WHERE market = 'IN' AND schedule_class = 'IN_X';

-- The date until which the retained duplicate copy (the photo) must be kept: the last sale against the record + the class's
-- retention_years. NULL = no retention rule applied. Nothing deletes paper_rx files today; a future purge must honour this date
-- and must also keep a row with duplicate_copy_retained and no date (recorded before this migration).
ALTER TABLE paper_prescriptions ADD COLUMN retain_until date;
