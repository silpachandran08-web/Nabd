# Child data controls (IDC-008): evidence for the DPDP pack

Scope: patients under 18 (DPDP Rule 10, CMP-04/DPO-09). This page maps each control to the code and the test that proves it.
It carries no legal wording; the notice text itself is the clinic's, built from the locked templates (IDC-003).

| Control | Where it is enforced | Proof |
|---|---|---|
| No marketing or recall-promo send about a child, to the child or to the guardian's number | `SendGate.check` in `services/comms-gateway` refuses with reason `minor-subject` before any consent lookup; the refusal is written to `send_refusals` (reason only, no date of birth) and counted in `comms_send_refused_total{reason}` | `ConsentGateDbTest.aChildSubjectNeverGetsMarketing...` |
| Age is evaluated at send time in the clinic's time zone, never stored as a flag | `MinorSubject.isMinor(dob, clinicZone, clock)`; the date of birth arrives with the send command and is not stored by Comms. A 29 February birthday counts as 1 March in a common year | `ConsentGateDbTest.eighteenthBirthdayIsDecidedInTheClinicZone` (Asia/Kolkata, Asia/Dubai, America/Los_Angeles, day boundaries) |
| Turning 18 does not switch marketing on | Marketing needs the patient's own marketing consent in the channel-consent snapshot; Core never records a marketing grant for a minor | `ConsentGateDbTest.turning18AllowsMarketingOnlyAfterTheirOwnConsent...`, `Idc004ConsentCaptureApiTest.ac3_...` |
| Core refuses to record marketing consent for a minor | `ConsentCaptureService` returns 422 `minor-marketing-blocked` | `Idc004ConsentCaptureApiTest.ac3_...` |
| Guardian consent is verifiable and versioned | `consents.guardian_patient_id` + `notice_version` + `captured_by` + `channel`, and `consent.changed.v1` carries `guardianRef`; capture needs the guardian linked on the patient (422 `guardian-required`) | `Idc004ConsentCaptureApiTest.ac3_...` |
| Audiences exclude under-18 profiles server-side | `MarketingAudience.adults` (the only audience query): dob known and 18 or older on the clinic day, marketing and processing consent granted | `Idc004ConsentCaptureApiTest.idc008_marketingAudienceExcludes...` |

Open items (not provable in this repository)
- Wiring `SendGate` into the live send pipeline and carrying `subjectDob` and the clinic zone on the send command waits on COM-010 (sender) and COM-004 (send command). Until then the gate is a tested component, not yet on the path.
- The Comms consumer reads `ev:<region>:comms`; `consent.changed.v1` is published to `ev:<region>:consent`. The consumer must also subscribe to that stream (see the IDC-006 report).
- Packages upsell and referral rewards (T-361) have no audience code yet; they must use `MarketingAudience`.
- Emergency treatment without processing consent needs a statutory basis per market: the wording is for the DPO to confirm.
