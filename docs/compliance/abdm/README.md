# ABDM M1 sandbox run and submission pack (IDC-019)

M1 submission is a Phase 1 exit item (RM section 5). This folder holds what can be prepared in the repository; the sandbox run,
the NHA submission and the audit booking are external steps.

| File | What it is |
|---|---|
| `m1-test-cases.json` | The M1 checklist mapped to what Nabd implements (IDC-016 bridge, IDC-017 ABHA, IDC-018 scan and share). `nha_ref` stays `null` until the compliance lead maps each case to its row in the official NHA M1 test sheet (the sheet comes with sandbox access and is not in this repository; no case text here is NHA wording). Auto cases are HTTP checks; manual cases need an OTP or a person and are recorded with evidence. |
| `m1-submission.json` | The submission record: NHA acknowledgement reference, CERT-In empanelled auditor and audit date, Phase 2 start date. All `null` until the owners fill them. Never fill a placeholder. |
| `../../../scripts/abdm/m1_runner.py` | Runner: `check`, `run`, `scan`, `gate` (see its docstring). Stdlib only. |
| `../../../scripts/abdm/test_m1_runner.py` | Its unit tests (`python3 scripts/abdm/test_m1_runner.py`). |

## Running against the sandbox (compliance lead, with a QA engineer)
1. Configure a staging clinic for the ABDM sandbox (`docs/runbooks/abdm-bridge.md`: sandbox client id/secret in the regional secret
   store, `ABDM_BASE_URL`, HFR/HPR lookups, `ABDM_JWKS_URI`), turn ABDM on in Setup > Integrations with the sandbox HFR id.
2. Use sandbox test identities only. Create a test patient and export `M1_PATIENT_ID` (its id) and `M1_ABHA_ADDRESS` (a sandbox ABHA address).
3. Sign in as the owner and export the access token as `NABD_M1_TOKEN` (it is sent as a bearer token and never written to the pack).
4. Run the manual cases in the browser, save screenshots under the pack folder, and write their results to a JSON file:
   `{"M1-03": {"result": "pass", "evidence": ["shots/m1-03.png"], "note": ""}, ...}`.
5. `python3 scripts/abdm/m1_runner.py run --base https://<staging-api>/ --out docs/compliance/abdm/evidence/<date> --manual results.json`
   writes `summary.json`, `summary.md` and one redacted request/response file per auto case. Names, phones, Aadhaar, OTPs and
   emails are masked by key, and any 10+ digit run or email is masked anywhere.
6. `python3 scripts/abdm/m1_runner.py scan docs/compliance/abdm/evidence/<date>` must report 0 lines before anything is committed
   or sent. Screenshots are not scanned: check them by eye for real data.

## Submission and audit
- Product owner (with the compliance lead): fill the NHA submission form from the pack, submit, and record `submitted_on`,
  `evidence_pack` and `nha_acknowledgement_ref` in `m1-submission.json`.
- Security lead (procurement): book a CERT-In empanelled auditor for the Phase 2 audit (IDC-020) and record `auditor`,
  `cert_in_empanelled: true`, `audit_date` and `booking_ref`; the product owner records `phase2_starts_on` from the plan.
- `python3 scripts/abdm/m1_runner.py gate docs/compliance/abdm/evidence/<date>` answers MET only when every mandatory case passed
  and is mapped to the NHA sheet, the pack scan is clean, the acknowledgement is recorded and the audit date falls in Phase 2.

## Status (Sprint 21)
Built and tested here: the checklist, the runner, redaction, the PHI scan of the pack and the exit gate. Not done (external):
the sandbox run (needs sandbox credentials and the NHA sheet), the submission and acknowledgement, and the auditor booking.
