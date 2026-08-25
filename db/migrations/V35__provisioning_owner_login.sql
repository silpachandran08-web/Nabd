-- NB-353: verify_invite_owner actually creates the owner's login now, instead of just logging a
-- mock invite line. It captures the owner's mobile at provisioning time so the EXISTING WhatsApp-
-- OTP-based PIN reset (AuthService.requestPinReset/confirmPinReset) is usable by this owner from
-- day one, without inventing a second "how do I recover my PIN" mechanism.
--
-- Nullable, not required: existing/older provisioning_jobs rows (already-completed jobs) predate
-- this column and have no mobile number to backfill — safe on a live database with real rows.
ALTER TABLE master.provisioning_jobs ADD COLUMN owner_mobile text;
