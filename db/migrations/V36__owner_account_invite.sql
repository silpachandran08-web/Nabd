-- NB-354: owners.pin_hash has always been "null until first login/activation" (see V6's own
-- comment) but nothing ever populated it — there was no invite/accept mechanism for an owner's
-- own top-level PIN, only for their per-tenant staff row (staff.invite_token_hash, V2). Mirrors
-- that exact same shape for owners, so OwnerService's already-built login/workspace-select flow
-- (NB-350) becomes reachable for a real owner instead of only test-seeded ones.
ALTER TABLE owners ADD COLUMN invite_token_hash text UNIQUE;
ALTER TABLE owners ADD COLUMN invite_expires_at timestamptz;
