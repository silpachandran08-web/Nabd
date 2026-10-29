# UAT script: Billing

Persona: `billing`. Checkout, invoices, receipts, sharing, refunds, day close.
Pass/fail per step in the run sheet; a failed step or a missed target is a triage row (`../triage.md`). Targets marked `record` have no agreed number yet: record the time, do not fail the step.

| Step | Journey | Action | Expected result | Target |
|---|---|---|---|---|
| BIL-01 |  | Open Checkout for a visit sent by the doctor | Suggested lines match the visit; each line shows its price and tax | record |
| BIL-02 |  | Complete checkout with a paid consult and one taxable procedure | Invoice created; totals match the screen to the paisa | record |
| BIL-03 |  | Press Print receipt (or Enter on the success state) | 80 mm receipt prints within 2 s | record |
| BIL-04 |  | Print the GST invoice on A4 | Shows clinic GSTIN, invoice number, SAC per line, exempt and taxable separately, CGST/SGST or IGST, amount in words | record |
| BIL-05 |  | Reprint the invoice | Marked Duplicate copy with the reprint time | record |
| BIL-06 |  | Checkout to printed receipt, start to finish, as a stopwatch run | Receipt printed | median ≤ 60 s |
| BIL-07 |  | Send the invoice on WhatsApp | Patient receives a secure link, no file in the chat; a patient without consent or phone gets a clear message | record |
| BIL-08 |  | Void or refund a line (maker-checker where enabled) | Needs the second approver; audit entry written | record |
| BIL-09 |  | Run day close | Expected cash matches the cash drawer; variance beyond tolerance needs a note | record |
