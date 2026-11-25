# UI gap backlog

Created 2026-10-11 from the wireframe-vs-built UI review (303 findings, 9 roles/surfaces) and the live verification pass.

**Source of truth:** `ui-gap-backlog.csv` (one row per finding, in git). A working copy with filters, dropdowns and live summary formulas is the workbook `nabd-ui-gap-backlog.xlsx` in the local `ui-gap-report/backlog/` folder. If the team edits the workbook, export the Backlog sheet back to this CSV so the two do not drift.

## Columns
Backlog ID, Finding ID (PLT/OWN/REC/DOC/NUR/PHA/PAT-F/CGV/AGG-F), Role, Screen / area, Title, Type, Severity, Priority, Wave, Epic / theme, Primary agent, Supporting agents, Mandatory review, Status, Readiness, Readiness reason, API verified / missing, Depends on, Dev size, Live check, Live note, Related NB tasks, Related findings, Wireframe says, Built does, Evidence, Suggested fix, Done when, Owner / sprint / notes, Source report.

## Rules used
- **Priority:** P0 = Blocker, or a Major item that needs a clinical-safety review. P1 = other Major. P2 = Minor.
- **Waves:** 1 = all P0. 2 = Major items in a cross-cutting epic (XC-1 i18n/RTL, XC-2 responsive, XC-3 shell and navigation, XC-4 permission gating). 3 = other Major. 4 = Minor.
- **Agents and reviewers:** keyword rules on the finding text. They are starting points, not decisions.
- **Readiness (2026-10-11):** Ready now = frontend-only, every API it needs exists today, no open design decision, no prerequisite item (103 items; list in `ui-gap-ready-now.md`). Otherwise Needs backend first (102), Needs decision (51) or Depends on item (45). Reviewers checked the backend controllers and the backlog text; they did not open every frontend file or the wireframe HTML, so spot-check the 'API verified' note before starting an item.
- **Dev size:** S <1 day, M 1-3 days, L >3 days for one developer on the first usable slice. A reviewer estimate, not a commitment.
- **Status:** Open, In progress, In review, Blocked, Done, Closed - refuted, Won't fix. REC-055 and DOC-053 start as 'Closed - refuted' (the live site showed no such leak). NUR-001 stays open but was reworded by the live check.
- **Live check:** Confirmed / Refuted / Could not observe (from the live pass); 207 rows were not checked live because the QA tenant has no data.

## Caveats
Evidence cites `Nabd Shell.dc.html` line numbers and frontend `file:line` from the local copy of the repo on 2026-10-11. Re-check against branch `develop-initial` before fixing. Older wireframe snapshots were not diffed. QA bugs QA-01..08 and live defects LIVE-01..09 are not in this CSV; they are in the workbook's 'Additional' sheet.
