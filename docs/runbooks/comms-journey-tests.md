# Comms journey and chaos tests (COM-010)

Everything lives in `services/comms-gateway/src/test/java/com/nabd/comms/` and runs with
`mvn -f services/comms-gateway/pom.xml test -Dtest=JourneyTest,SendSimulationTest,Com021QualityDbTest`.

## Pieces
- `testkit/ProviderStub` implements the `Provider` port. Modes: `NORMAL`, `DOWN`, `STORM_429`, `LATENCY` (accepted, then the call times out:
  a retry duplicates, the stub counts it), `PARTIAL_5XX`. A recipient starting `+bad` is rejected (131026). `statusWebhooks(mode)` replays delivery
  callbacks `ORDERED`, `DUPLICATED`, `REORDERED` or both. The stub keeps the ground truth: `acceptedMessages()`, `duplicates()`, `truthByWamid()`.
- `testkit/VirtualPatient` is a scripted WhatsApp user: `say("Hi")`, `tap("book")` post a Meta-shaped inbound webhook signed with the app secret;
  `expectReply("...")` asserts the next reply; `elapsed()` reports the journey time.
- `testkit/TestClock` is a manual clock; the sender loop takes a `Clock`, so 30 minutes of outage run in milliseconds.

## Write a journey
1. Create a `ProviderStub` and your bot (anything that takes a `Webhook`, checks `X-Hub-Signature-256`, and replies through `Provider.send`).
2. `new VirtualPatient("+1555...", "pn1", secret, bot::receive, stub)`; fake numbers only, never a real patient.
3. Chain the steps and assert: `p.say("Hi").expectReply("Welcome").tap("book").expectReply("Pick a slot")`.
4. Assert the end state (booking made) and `p.elapsed()` (the booking SLO T-162 is a median under 60 s end to end).
See `journey/JourneyTest` (book, reschedule, STOP, wrong signature).

## Write a chaos test
Use `SendSimulationTest.Sim`: add messages to lanes, set `sim.stub.mode`, `sim.run(seconds)`, assert on `sim.sentAt`, `sim.dead`, `sim.stub.duplicates()`.
A message is never dropped: it is sent, queued, or dead-lettered (`outbound_messages.status = 'failed'` with `error_code`).

## Not covered here (Needs external action)
- Real Meta calls: the nightly `live-sandbox` job in `.github/workflows/comms-sandbox-nightly.yml` is gated by repository variable
  `COMMS_LIVE_SANDBOX` and needs the Meta Cloud client (COM-007), `META_TEST_ACCESS_TOKEN`, `META_TEST_PHONE_NUMBER_ID` and `SQUAD_CHANNEL_WEBHOOK` secrets.
- k6 and Toxiproxy are not available in this sandbox; the burst and fault modes run in-process against the stub.
