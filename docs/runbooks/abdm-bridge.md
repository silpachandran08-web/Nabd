# ABDM bridge (IDC-016) and clinic privacy notices (IDC-003): operator notes

## ABDM bridge
- Off by default per clinic (DEC-33). The owner turns it on in Setup > Integrations with the facility's HFR id and an authenticator code; the HFR id is checked against the Health Facility Registry before anything is stored. A clinic with it off never causes an ABDM call (`AbdmService` is the only caller of the gateway port and checks the switch first).
- Doctors' HPR ids: `PUT /v1/staff/{id}/hpr` verifies against HPR and stores `staff.hpr_id`; it answers 409 `abdm-disabled` while the clinic has ABDM off.
- Credentials live only in the regional secret store as platform secrets `abdm-client-id` and `abdm-client-secret` (OPS-058 `SecretResolver`). Nothing is read from config or the database.
- Config (`app.abdm.*`, env `ABDM_BASE_URL`, `ABDM_CM_ID`, `ABDM_HFR_LOOKUP`, `ABDM_HPR_LOOKUP`, `ABDM_JWKS_URI`). A blank base URL, blank lookup template or missing credentials answer 503 `abdm-not-configured` and make no network call. Callbacks (`POST /abdm/v3/callbacks/**`) answer 401 until `ABDM_JWKS_URI` is set; with it set, only a token signed by the gateway's key set gets 202.
- The gateway client uses the shared resilient client (provider `abdm`): connect/read timeouts from `app.integration`, circuit breaker (5 failures, 30 s), refused inside a DB transaction. The session token is fetched on first use and refreshed 60 s before it expires; a 401 drops it and retries once.

### Needs external action (not done in this repository)
1. ABDM sandbox/production registration of the Nabd bridge and the callback URL per region (DEP-05 lead time), and the client id/secret placed in the regional secret store.
2. The HFR and HPR lookup URL templates and response contract from the sandbox (`ABDM_HFR_LOOKUP`, `ABDM_HPR_LOOKUP`), the gateway's JWKS URL and the correct `X-CM-ID`. `HttpAbdmGatewayTest` replays a fixture shaped after the published v3 session exchange, not a recording of the real sandbox; re-record it once access exists.
3. The 24-hour token refresh soak against the real sandbox (unit test `AbdmSessionTokensTest` simulates 24 h with a fake clock only).

## Privacy notices
- `notice_templates` (platform-owned, read-only to the API role) holds the Nabd templates per market and language. **The mandatory clauses seeded by V2280 are placeholders** (`[PENDING DPO WORDING ...]`, `legal_reviewed = false`): no statutory wording is drafted in this repository. The owner screen and the public page both say the wording is unapproved until a new template version with the DPO-approved clauses is published (insert a new `notice_templates` row with a higher `version`, `legal_reviewed = true`).
- A clinic gets v1 in every language of its market's template the first time it saves its grievance contact (`PATCH /v1/setup/consent-contact`). Publishing is `PUT /v1/settings/privacy/privacy-notice` (setup:edit plus a step-up token); a version is immutable.
