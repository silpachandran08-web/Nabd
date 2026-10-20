# API contracts (ADR-10, OPS-035)

The committed contract is `api/openapi/core.yaml`. It is generated from the controllers (OPS-034) and checked in, so every operation change shows up as a spec diff that people review. CI stage 5 (`stage5-api-contract`, a required check) blocks the PR when:

| Check | Where | Fails with |
|---|---|---|
| A controller operation is not in `core.yaml` | `ApiContractGateTest` | `undocumented operation: GET /v1/... (Controller.method)` |
| `core.yaml` lists an operation the code does not have | `ApiContractGateTest` | `phantom operation: ...` |
| `x-required-permission` differs from the `@PreAuthorize` authority, in `core.yaml` or in `api/openapi.yaml` where that file documents the operation | `ApiContractGateTest` (SEC-24 endpoint registry) | `... x-required-permission X != code [Y]` |
| A breaking change against the base branch (any oasdiff `breaking` WARN or ERR) | `oasdiff 1.10.23` + `scripts/ci/api_contract.py` | `breaking change: ...` |
| A deprecated operation without a Sunset date | `scripts/ci/api_contract.py` | `deprecated without Sunset: ...` |
| The generated client is out of date | `scripts/api/generate_client.py --check` | `generated client is stale ...` |

The `api-contract-report` job still posts the oasdiff changelog as a PR comment. It never blocks.

## Changing an operation
1. Write or change the controller method, with `@PreAuthorize("hasAuthority('module:action')")`, or leave it out for `authenticated`. Public routes are the `PUBLIC` list in `OpenApiConfig` plus `SecurityConfig`.
2. Regenerate the spec: `mvn test -Dtest=OpenApiSpecApiTest -Dopenapi.write=true` (in `backend/`).
3. Regenerate the client: `python3 scripts/api/generate_client.py`.
4. If `api/openapi.yaml` documents the operation, keep its `x-required-permission` equal to the gate: one authority, or `a | b` for `hasAnyAuthority` / `or`. A further check done in the service (doctor is self, holder of the grant, licence) goes in `x-permission-check`, never in `x-required-permission`.
5. Commit `core.yaml`, `frontend/packages/api-client/src/generated/` and the code in one PR.

## Breaking changes and Sunset
`/v1` only grows. You may remove a field or tighten a type only on an operation that is already deprecated with a Sunset date:

```java
@Deprecated // springdoc writes deprecated: true
@io.swagger.v3.oas.annotations.Operation(extensions = @io.swagger.v3.oas.annotations.extensions.Extension(
        properties = @io.swagger.v3.oas.annotations.extensions.ExtensionProperty(name = "x-sunset", value = "2027-03-31")))
```

The response should also send the RFC 8594 `Sunset` header. To remove a whole operation, deprecate it with `x-sunset` first. oasdiff refuses the removal (`api-removed-before-sunset`) until that date has passed. Changes to the sunset date are never exempt.

## Generated client
`frontend/packages/api-client/src/generated/` is written only by `scripts/api/generate_client.py` (python3 + PyYAML, no npm tool, about 1 s):
- `types.ts`: one TS type per schema, plus `Operations` (method, path, permission, params, body, response).
- `schemas.ts`: Zod schemas plus `operationSchemas` (imports `zod`).
- `handlers.ts`: `createHandlers({ http, HttpResponse }, baseUrl)` returns MSW handlers with deterministic example bodies. msw is passed in, so the package needs no msw dependency.
- `index.ts`: types and schemas only, so app bundles never include the mocks.

FEF-012 owns the package manifests (it must declare `zod`, and `msw` as a devDependency). FEF-014 moves the app's calls onto these types.

## New modules: interface-first (openapi-generator `interfaceOnly`)
A new module or service writes its operations in a spec file first and implements generated interfaces. Add this to `backend/pom.xml` when the first such module lands. No module needs it yet, so it is not wired:

```xml
<plugin>
  <groupId>org.openapitools</groupId><artifactId>openapi-generator-maven-plugin</artifactId><version>7.10.0</version>
  <executions><execution><goals><goal>generate</goal></goals><configuration>
    <inputSpec>${project.basedir}/../api/openapi/&lt;module&gt;.yaml</inputSpec>
    <generatorName>spring</generatorName>
    <apiPackage>com.nabd.hms.&lt;module&gt;.api</apiPackage><modelPackage>com.nabd.hms.&lt;module&gt;.dto</modelPackage>
    <configOptions><interfaceOnly>true</interfaceOnly><useSpringBoot3>true</useSpringBoot3><useTags>true</useTags><skipDefaultInterface>true</skipDefaultInterface></configOptions>
  </execution></executions>
</plugin>
```

The controller `implements` the generated interface and keeps its `@PreAuthorize`. The gate above still applies.

## Known gaps
- `api/openapi.yaml` (hand-written, older) documents only part of the code. It also lists 6 operations the code does not have (`known-defect-OPS-034`, pending decision 9). The gate compares permissions wherever that file documents an operation, and prints how much of the code it covers. Undocumented and phantom operations are counted against `core.yaml`.
- Event schemas in `api/events` (OPS-015) follow the same additive rule. No oasdiff-style gate exists for them yet.
