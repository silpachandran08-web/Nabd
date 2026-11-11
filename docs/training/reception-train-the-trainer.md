# ACC-035: Reception train-the-trainer pack and competency check

Owner: Product & Design with the pilot delivery lead (OPS-049). Audience: the person who trains a new receptionist at a pilot clinic.
Language: run the session in the receptionist's language (English or Hindi in India; Arabic when the Gulf launches). The app's tours,
help and checklist follow the language chosen in the account menu.

**Status: the pack, the in-app tours and the competency checklist exist. The vernacular videos and the go-live competency sessions
at pilot clinics are Needs external action** (recording and hosting by Product & Design; sessions by the pilot trainer). Nothing here
records a real trainee result.

## Before the session (trainer, 10 minutes)
1. The trainee has a staff account with the Receptionist role and has signed in once (the five-step reception tour starts by itself
   on the first sign-in; it can be replayed from Help, F1, or the account menu, "Replay the tour").
2. Use the pilot clinic's synthetic training patients only (OPS-049 rules: no real PHI, no screenshots of patients).
3. Open Help (F1) on Today / Arrivals: the "Front-desk competency check" lists the ten tasks below with a tick box each.

## The session (about 60 minutes)
Show each task once, let the trainee do it once while you watch, then the trainee ticks it in Help on their own account.
Ticks are saved to the trainee's account (any device). The tenth tick records "Passed on <date>" in the app and in the audit log.

| # | Key | Task the trainee does | Where |
|---|---|---|---|
| 1 | `task.walkin` | Register a walk-in patient (one new, one returning) | Today / Arrivals, W or F2 |
| 2 | `task.checkin` | Check in a booked patient with the right doctor and payment method | Waiting tab, F4 |
| 3 | `task.queue` | Move a patient to the next step in the queue | Arrivals board |
| 4 | `task.requests` | Accept or decline a booking request | Requests |
| 5 | `task.delay` | Send a delay message to waiting patients | Arrivals, delay broadcast |
| 6 | `task.checkout` | Take payment at checkout and hand over the receipt | Checkout |
| 7 | `task.reschedule` | Reschedule and cancel an appointment | Queue & Appointments |
| 8 | `task.noshow` | Mark a patient as a no-show | Queue & Appointments |
| 9 | `task.search` | Find a patient by name or phone and open their record | Patients, / key |
| 10 | `task.offline` | Recognise when the app is offline (status pill, offline page) and that payments must wait | Top bar |

## Competency rule
The trainee passes when all ten tasks are ticked after being done once under observation. A receptionist runs the desk alone at a
pilot clinic only after passing. The trainer records the session in the OPS-049 shadow-mode log (`docs/uat/shadow-mode.md`) with
the staff id, never the trainee's or a patient's name.

## Videos (top five desk tasks, under 2 minutes each, English and Hindi)
Check-in, walk-in, requests, delay broadcast, checkout. Slots are in `frontend/apps/staff-web/app/shell/help.ts` (`DESK_VIDEOS`); the
Help panel says "not available yet" until a URL is filled in. Recording, native-language review and in-region hosting: Product &
Design (external). Do not link a video that has not been reviewed by a native speaker.

## Training the trainer
A trainer runs one supervised session with the delivery lead watching, using this pack, before training alone. Hindi tour and
checklist text needs a review by pilot receptionists (spec test: "content review by pilot receptionists"; external).
