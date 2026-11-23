SET lock_timeout = '5s';
-- COM-050: 'My records' on WhatsApp. A link the patient asks for in the clinic's chat has no staff creator: created_by is NULL
-- for those links (the audit row records.link_issued names the patient, the phone hash, the item and the channel instead), and
-- such a link is re-checked against the recipient's record scope on every code request and open.
ALTER TABLE secure_links ALTER COLUMN created_by DROP NOT NULL;
-- The stored invoice PDF (FIN-012) copied to the file store the first time a patient asks for a link to it.
ALTER TABLE billing_documents ADD COLUMN file_id uuid REFERENCES file_objects(id);
