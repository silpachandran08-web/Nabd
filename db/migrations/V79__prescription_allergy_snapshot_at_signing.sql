-- DOC-R3 (P0 clinical safety): the printed signed prescription must show the allergy statement the prescriber
-- signed against, not whatever the register says when someone presses Print.
--
-- allergy_snapshot is written by PrescriptionService.sign() in the SAME UPDATE that flips status draft -> signed,
-- while the patient's allergy-register lock is held, so it is exactly the register the server-side sign gate
-- checked. Shape (JSON object):
--   { "state": "recorded" | "none_documented" | "not_assessed",
--     "allergies": [ { "substance": text, "severity": text, "reaction": text|null } ],   -- active entries only
--     "documentedBy": uuid|null, "documentedByName": text|null, "documentedAt": ISO-8601|null }  -- "no known allergies" statement
--
-- Legacy rows signed before this migration keep NULL (expand-only, deliberately NOT backfilled: the register at their
-- signing time is unknown and reconstructing it would be invented data). Drafts are always NULL.
--
-- Immutability: V51's trg_prescriptions_signed_final refuses any UPDATE whose OLD.status is 'signed', so the snapshot
-- can only be written by the signing UPDATE itself (OLD.status = 'draft') and never changed afterwards. V51 is not
-- edited and needs no change. The CHECK below adds the other half: a draft can never carry a snapshot.

ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS allergy_snapshot jsonb;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'prescriptions_allergy_snapshot_shape' AND conrelid = 'prescriptions'::regclass) THEN
    ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_allergy_snapshot_shape CHECK (
      allergy_snapshot IS NULL
      OR (status = 'signed'
          AND jsonb_typeof(allergy_snapshot) = 'object'
          AND allergy_snapshot->>'state' IN ('recorded', 'none_documented', 'not_assessed')
          AND jsonb_typeof(allergy_snapshot->'allergies') = 'array')
    );
  END IF;
END $$;

COMMENT ON COLUMN prescriptions.allergy_snapshot IS
  'DOC-R3: allergy statement (status + active allergies) captured at signing; NULL = draft, or signed before V79 (not recorded).';
