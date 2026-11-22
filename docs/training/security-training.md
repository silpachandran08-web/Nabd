# PLT-054: Security and data-handling training (Nabd team and clinic staff)

Owner: the DPO (operator role `compliance_dpo`), with Product & Design for the content. Tracker: T-319 (SC-67, NFR-09).
Audience: every Nabd employee with an operator account, and every staff account of a clinic that holds real patient data.

**Status: the in-app module (English and Hindi), the quiz, completion tracking, the go-live blocker and the team lists exist.
Needs external action: the DPO's review and sign-off of the module text, a native-speaker review of the Hindi text (it was
drafted with the English and has not been reviewed), the vernacular voice-over for clinic staff, and the Nabd-only onboarding
session below. Nothing here records a real person's result.**

## The rule
| Who | When | Where they take it | Who checks |
|---|---|---|---|
| Clinic staff (every active account) | Before the clinic goes live, then every year | Help (F1) in the clinic app, "Data safety training" | The owner: Staff & Access, "Data safety training" list. Go-live is blocked until every active account has a current pass (`staff_phi_training`). |
| Nabd operators | In the first week after the account is created, then every year | Operator console, Command Centre, "Data safety training" (`/platform/training`) | The DPO: Audit & Compliance, "Regulatory watch & training" (`/platform/audit/compliance`) |

A pass is current for one year from the day it was recorded. After that the person shows as overdue until they pass again.
An invited clinic account that has not signed in yet does not count towards go-live; it shows as "Not completed" once it joins.

## The module (`phi-handling`, about 6 minutes)
Text lives in the help catalogue (`frontend/packages/i18n/messages/<locale>/help.json`, key `training`), English and Hindi.
Other languages fall back to English until they are translated.

| Part | What it covers |
|---|---|
| Why this matters | What patient information is; trust, the law and harm from a leak |
| Fake messages and calls | Phishing: Nabd never asks for a PIN or one-time code; unexpected links and files; report a mistake |
| Handling patient information | Need-to-know access; no photos, screenshots or personal copies; speaking at the desk; prints |
| WhatsApp and messages | Never forward reports, prescriptions or photos on personal WhatsApp; only the clinic's Nabd messages and secure links; no patient details in groups; wrong-number sends |
| Your account and devices | No shared sign-ins; lock the screen; sign out on shared computers; lost devices |
| If something goes wrong | Report at once to the owner; never delete to cover a mistake |

Five questions follow, one per habit (personal WhatsApp, a caller asking for a PIN, leaving the desk, a shared sign-in, a
wrong-number send). The pass mark is 80 out of 100 (four of five). A failed attempt is not recorded; the person reads again
and retakes it. The quiz is marked in the browser and the score is recorded as sent: it is an attestation, like a signed
attendance sheet, not an exam.

## Nabd track (in addition to the module)
The module is the common base. Nabd employees also need, in their first week, a 30-minute session with the DPO on:
support access (PLT-056/057: no standing access, every grant is requested and audited), handling of production data and
exports, the incident runbook, and secrets on laptops. **Needs external action (DPO): write and run this session; record
attendance outside the app.** The app tracks only the module.

## Records
- Clinic staff: table `training_completions` (per clinic, row-level security), audit action `staff.training_completed`.
- Nabd operators: table `master.operator_training_completions`, platform audit action `operator.training_completed`.
- API: `POST /v1/training/completions { moduleId, score }`, `GET /v1/training/completions/me`, `GET /v1/training/completions`
  (staff:view); the same three under `/v1/platform/training/completions` for operators (the list needs audit_compliance:view).
