# FHIR R4 mapping of a signed consult (IDC-055)

Module `exchange` (`com.nabd.hms.exchange`). One signed consult becomes plain-JSON FHIR R4 resources shaped for the
NRCeS / ABDM implementation guide (package `ndhm.in`, version 6.5.0). No HAPI, no new dependency: Jackson nodes.

## How to call it

`com.nabd.hms.exchange.api.ConsultFhir` (Spring bean, no web request needed):

- `map(Consult)`: pure. Takes already-loaded records, returns the resources. No database, no clock.
- `forVisit(queueEntryId)`: loads the visit's signed consult through the owning modules' APIs and maps it. Must run
  inside a tenant transaction (a worker: `TenantScope.run(tenantId, () -> consultFhir.forVisit(id))`). Empty when the
  visit is unknown in that tenant or its note is not signed.

Order of the result: Composition, Patient, Encounter, Organization, Practitioner(s), MedicationRequest(s). Building a
Bundle (document, collection) is the caller's job (IDC-044).

**The caller owns authorisation.** This is a system read: it checks no consent, no read scope and no field grant, and
writes no audit or `phi_read_log` row. A flow that sends the result anywhere must check consent and purpose first and
record the disclosure.

## Where the data comes from

| Data | Owner API (new in IDC-055) |
|---|---|
| Signed note, its amendments, signed Rx lines | `clinical.api.SignedConsults` |
| Encounter id (`queue_entries.encounter_id`), status, start | `queue.api.VisitEncounters` |
| Record number, name, sex, date of birth | `patient.api.PatientDemographics` |
| Name, verified HPR id, council registration number | `iam.api.PractitionerRecords` |
| ABHA number and address (unsealed in memory), verified HFR id | `identity.api.HealthIds` |
| Clinic name | `tenants.name` (the module's only SQL read) |

## What is written

| Resource (profile) | Content |
|---|---|
| Patient (`Patient`) | identifiers below, `name.text`, `gender`, `birthDate`. Phone, address and the sealed national id are never read. |
| Encounter (`Encounter`) | id = encounter id, class `AMB`, status (`completed` -> finished, `no_show` -> cancelled, anything else -> in-progress), subject, participant (visit doctor), `period.start`, serviceProvider. |
| Practitioner (`Practitioner`) | one per person named: visit doctor, note signer, Rx signer, amendment authors. |
| Organization (`Organization`) | clinic name and HFR id. |
| MedicationRequest (`MedicationRequest`) | one per signed Rx line: status active, intent order, medicine as text, dosage text (dose, pattern, frequency, duration), patient instruction, route as text, quantity. |
| Composition (`OPConsultRecord`) | type SNOMED CT 371530004; a Medications section (721912009) listing the MedicationRequests; then Subjective, Objective, Assessment, Plan and Diagnosis as titled narrative sections; then one Amendment section per amendment, oldest first. Status `amended` when there is an amendment. |

Identifiers:

| Identifier | type | system | Note |
|---|---|---|---|
| Clinic record number | v2-0203 `MR` | `urn:nabd:tenant:<tenantId>:mrn` | unique inside one clinic only |
| ABHA number | v2-0203 `MR` | `https://healthid.ndhm.gov.in` | as in the IG's Patient example; written as 2-4-4-4, only when it has 14 digits |
| ABHA address | ndhm-identifier-type-code `ABHA` | none | the IG publishes the type code but no system URI |
| HPR id (verified) | v2-0203 `MD` | `https://doctor.ndhm.gov.in` | as in the IG's Practitioner example |
| Council registration number | v2-0203 `MD` | none | the issuing council is not recorded |
| HFR id (verified) | v2-0203 `PRN` | `https://facility.ndhm.gov.in` | as in the IG's Organization example |

Rules the mapper keeps:

- **Nothing is invented.** A value the record does not hold is left out. Free text stays text: the SOAP fields and the
  diagnosis get no code and no Condition / Observation resource; a medicine is `medicationCodeableConcept.text`.
- **No Aadhaar.** Aadhaar is not an identifier type anywhere in the schema. As a second line of defence an identifier
  whose whole value is 12 digits is dropped, and a 12-digit number (bare or 4-4-4) inside any free-text value keeps only
  its last four digits (`XXXXXXXX1234`).
- **A signed note is never rewritten.** Amendments follow the original; they are not merged into it.

## Known gaps (decisions needed)

1. **Drug master code is not written.** The NRCeS MedicationRequest profile fixes `medication[x].coding.system` to
   SNOMED CT and the drug master carries no SNOMED code, so a coded line is exported as its text. When the drug master
   gains SNOMED codes, add the coding in `FhirMapper.medicationRequest`.
2. **ICD-10.** CLN-012 (structured diagnoses) is built in parallel; when it lands, map `visit_diagnoses` to Condition
   resources and reference them from the Composition.
3. **Clinics without registry ids.** Patient, Practitioner and Organization need at least one identifier in the NRCeS
   profiles. A doctor with neither an HPR id nor a registration number, or a clinic without a verified HFR id, gives a
   resource without identifier that the profile will reject. An ABDM-enabled clinic always has an HFR id.
4. **An Rx line with only a drug name** has no `dosageInstruction` (the profile wants one).
5. **PractitionerRole** is not produced (staff role and specialty are not mapped to coded values).
6. **Encounter class** is always `AMB`: every visit is ambulatory today (`queue_entries.class`).

## Tests

- `FhirGoldenFileTest`: 32 synthetic consults, each compared byte for byte with its committed Bundle in
  `backend/src/test/resources/exchange/fhir/golden/`; also checks the mandatory elements read from the IG and that every
  reference resolves. Runs in CI stage 2 on every backend change. After an intended mapping change, rewrite the files
  with `mvn test -Dtest=FhirGoldenFileTest -Dfhir.golden.write=true` (alone) and review the diff.
- `FhirMapperTest`: identifiers, Aadhaar-shaped input in every field, missing data, XHTML escaping, purity, speed.
- `Idc055ConsultFhirApiTest`: a real signed consult in the database, read without a web request.

## Profile validation (not yet run)

`scripts/ci/fhir_validate.sh` runs the HL7 FHIR validator over the golden bundles:

```
FHIR_VALIDATOR_JAR=/path/to/validator_cli.jar scripts/ci/fhir_validate.sh
```

- `FHIR_IG` (default `ndhm.in#6.5.0`): the guide, as a package id or a local `package.tgz`
  (`https://nrces.in/ndhm/fhir/r4/package.tgz`).
- `FHIR_VALIDATOR_ARGS`: extra arguments, for example `-tx n/a` where the terminology server cannot be reached.

The jar is not in the repository and CI does not download it. The CI step "FHIR profile validation" (stage 2) calls the
script; without the jar it prints `SKIPPED` and passes. To switch it on: put a pinned, checksum-verified
`validator_cli.jar` on the runner (the way `scripts/ci/install-trivy.sh` installs Trivy) and set the repository variable
`FHIR_VALIDATOR_JAR` to its path. Until that run shows zero errors, the acceptance criterion "validates against the
NRCeS profiles with zero errors" is open.
