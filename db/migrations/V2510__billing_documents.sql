SET lock_timeout = '5s';
-- FIN-012: one stored PDF per (invoice, kind, layout, version). A reprint returns the stored bytes, so it shows the values
-- of the original issue even after prices change. content is the rendered file; object_key is the vault path it will move to
-- when the encrypted object store lands (Needs external action), the column already carries the per-tenant prefix.
CREATE TABLE billing_documents (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  invoice_id  uuid NOT NULL REFERENCES invoices(id),
  kind        text NOT NULL CHECK (kind IN ('invoice','receipt','credit_note','estimate','claim_pack','payout_statement')),
  layout      text NOT NULL CHECK (layout IN ('a4','thermal80')),
  version     int  NOT NULL CHECK (version >= 0),
  object_key  text NOT NULL,
  sha256      text NOT NULL,
  content     bytea NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, invoice_id, kind, layout, version)
);
SELECT enforce_tenant_rls('billing_documents');
