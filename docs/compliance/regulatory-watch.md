# PLT-054: Regulatory watch (monthly review, log and quarterly summary)

Owner: the DPO (operator role `compliance_dpo`). Tracker: T-330 (SSA-11, NB-256).
**Needs external action: name the person who holds this role and their deputy here before the first review. No review has
been run from this document yet; the record at the end is empty on purpose.**

- Named owner: _to be filled by the founder_
- Deputy: _to be filled by the founder_

## What is watched
| Source (log value) | Market | What to check each month |
|---|---|---|
| DPDP Rules (`DPDP`) | IN | Notified rules, Data Protection Board orders, consent-manager and breach-notice requirements |
| NCNICC (`NCNICC`) | to be confirmed by the DPO | Listed in SSA-11; the DPO states here what is checked and for which market before the first review |
| ABDM (`ABDM`) | IN | Sandbox and production policy, milestone and certification changes |
| TRAI DLT (`TRAI_DLT`) | IN | Template, header and consent-registration rules for SMS |
| ZATCA (`ZATCA`) | SA | E-invoicing waves, onboarding dates and specification versions |
| NABIDH (`NABIDH`) | AE | Policy and standards updates for Dubai health information exchange |
| Meta (`META`) | ALL | WhatsApp Business policy, template categories, pricing and messaging limits |
| Anything else (`OTHER`) | as it applies | A regulator or platform not listed above |

Market is a code from the regions table, or `ALL` when the change applies everywhere.

## The monthly ritual (first working week of each month)
1. Go through every source above, including the ones with no known change. Note the date of the review in the record below.
2. For each change, open the operator console: Audit & Compliance, "Regulatory watch & training", and log it with:
   source, market, impact level (none, low, medium, high), what changed, what it means for Nabd, the day it was noticed, and
   the tracker task (`T-n`) or decision (`DEC-n`) that carries the follow-up. Create the task or decision first; an entry
   cannot be saved without one.
3. Target: every change is logged within five working days (Monday to Friday) of the day it was noticed. A later entry is
   still saved and is marked late in the log and in the quarterly summary. Do not adjust the noticed date to avoid that.
4. A change that needs a correction gets a new entry that refers to the same task. Entries are never edited or removed.
5. A month with nothing to log still gets a line in the record below, so that "no change" can be told from "no review".

## The quarterly summary (first week after each quarter)
1. In the same screen choose the year and quarter and select "Download the quarterly summary". The file
   `regulatory-watch-<year>-Q<n>.md` lists the quarter's changes, the late ones, the months with nothing logged and the Nabd
   team's training state. The download is written to the platform audit log.
2. Add a short covering note: what needs a founder decision, and anything late and why.
3. Send it to the founder. **Sending is a manual step (Needs external action, DPO): the app prepares the file and does not
   send email.** Record the date sent below.

## Record of reviews and summaries
| Month or quarter | Reviewed or sent on | By | Changes logged | Notes |
|---|---|---|---|---|
| _none yet_ | | | | |

## Where it lives
- Log: table `master.regulatory_watch_entries`; API `GET` and `POST /v1/platform/regulatory-watch`,
  `GET /v1/platform/regulatory-watch/summary?year=&quarter=` (audit_compliance:view: super_admin and compliance_dpo).
- Audit actions: `regulatory_watch.recorded`, `regulatory_watch.summary_exported`.
- Working days are Monday to Friday with no public-holiday calendar.
