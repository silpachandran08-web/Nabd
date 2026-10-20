# ADR 0017: Spring Boot 4.1 on Java 21 with virtual threads

- **Status: ACCEPTED (engineering), PLT-032 (T-078), Sprint 12.** Supersedes the "Boot 3.3 / Java 17" row of the stack table (doc06 D4, ADR-02).
- Date: 2026-10-20. Reviewers needed: `devops-sre` (images, CI), `security-privacy` (Security 7 behaviour).

## 1. Problem

Core was on Spring Boot 3.3.4 / Java 17. Boot 3.3 lost OSS support in 2025. The story asks for the newest **supported** 3.x line, or 4.x if no 3.x line is supported any more.

## 2. What is supported on 2026-10-20

| Line | OSS support ends | Latest on Maven Central today |
|---|---|---|
| 3.5 (last 3.x) | **2026-06-30 (ended)** | 3.5.x |
| 4.0 | 2026-12 (two months left) | 4.0.8 |
| 4.1 | 2027-07 | **4.1.1** |

Sources: spring.io support policy as summarised by endoflife.ai (`/spring-boot/4.0`) and HeroDevs ("Spring Boot 3.5 is officially end of life", 2026); versions from `repo.maven.apache.org/.../spring-boot-starter-parent/maven-metadata.xml`.
No 3.x line is supported, so the choice is 4.x. 4.0 ends in two months, so we take **4.1.1**.

## 3. Decision

- `spring-boot-starter-parent` 4.1.1, `java.version` 21, `spring.threads.virtual.enabled=true`.
- Aligned libraries: Spring Framework 7.0, Spring Security 7.1, Spring Modulith 2.1.1, springdoc 3.1.1, ShedLock 7.10.1,
  Testcontainers 2.0.5 (Boot-managed, replaces the OPS-031 1.21.3 pin), Flyway 12, Lettuce 7, JUnit 6, PostgreSQL JDBC 42.7.13.
- Images: `eclipse-temurin:21-jre-alpine` (layer extraction) and `gcr.io/distroless/java21-debian12:nonroot` (runtime). CI: every `setup-java` is `21`.

## 4. Consequences (what changed and what every engineer must now do)

1. **Jackson 3.** Boot 4 auto-configures Jackson 3 (`tools.jackson.*`). Our code moved mechanically:
   `com.fasterxml.jackson.databind.*` -> `tools.jackson.databind.*`, `com.fasterxml.jackson.core.type.TypeReference` -> `tools.jackson.core.type.TypeReference`,
   `JsonProcessingException` (checked) -> `tools.jackson.core.JacksonException` (unchecked), `JsonNode.asText(..)` -> `asString(..)`, `fieldNames()` -> `propertyNames()`.
   Annotations stay in `com.fasterxml.jackson.annotation`. `ObjectMapper` is immutable: configure through `JsonMapper.builder()`, not `enable(..)`.
   Jackson 2 jars are still on the classpath (swagger-core needs them), so **new code that imports `com.fasterxml.jackson.databind.ObjectMapper` still compiles but has no Spring bean and fails at context start.**
   The re-apply recipe for code written against the old tree:
   ```
   sed -i -E 's/com\.fasterxml\.jackson\.databind\./tools.jackson.databind./g; s/com\.fasterxml\.jackson\.core\.type\.TypeReference/tools.jackson.core.type.TypeReference/g; s/com\.fasterxml\.jackson\.core\.JsonProcessingException/tools.jackson.core.JacksonException/g; s/\bJsonProcessingException\b/JacksonException/g; s/\.asText\(/.asString(/g; s/\bUNPROCESSABLE_ENTITY\b/UNPROCESSABLE_CONTENT/g' <files>
   ```
2. **Modular starters.** Boot 4 splits auto-configuration per technology. We now use `spring-boot-starter-webmvc`, `-security-oauth2-resource-server`, `-flyway`
   (Flyway is no longer configured by `flyway-core` alone), `-security-test`, plus test-only `spring-boot-resttestclient` + `spring-boot-restclient` for `TestRestTemplate`
   (`ApiTestBase` needs `@AutoConfigureTestRestTemplate`).
3. **Moved packages.** Health: `org.springframework.boot.health.contributor.{Health,HealthIndicator,Status}`, `org.springframework.boot.health.actuate.endpoint.HealthEndpoint`,
   `org.springframework.boot.jdbc.health.DataSourceHealthIndicator`; `EndpointRequest`: `org.springframework.boot.security.autoconfigure.actuate.web.servlet`;
   Redis auto-configs are `org.springframework.boot.data.redis.autoconfigure.DataRedis*AutoConfiguration` (the `spring.autoconfigure.exclude` list was renamed, otherwise Boot's
   localhost Redis would silently come back); `TestRestTemplate`: `org.springframework.boot.resttestclient`; Testcontainers: `org.testcontainers.postgresql.PostgreSQLContainer`.
4. **Removed / deprecated APIs.** `RedisURI.getUsername/getPassword` (Lettuce 7, read the URI's credentials provider instead); `HttpHeaders` is no longer a `Map` (`headerNames()`);
   `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated for `UNPROCESSABLE_CONTENT` (same 422).
5. **OpenAPI.** springdoc 3 defaults to OpenAPI 3.1; we pin `springdoc.api-docs.version=openapi_3_0` so the OPS-035 drift gate and client generator are unchanged. springdoc 3 now
   documents Bean Validation (`minLength: 1` for `@NotBlank`, `minimum`, `minItems`) and `LocalTime` as `string/time-local`; `api/openapi/core.yaml` and the generated client were regenerated.
6. **Virtual threads.** Tomcat requests, `@Async` and `@Scheduled` run on virtual threads. Java 21 pins a carrier inside `synchronized` around blocking I/O, so such code uses
   `ReentrantLock` (`RegistryProjector.drain`). pgJDBC 42.7.x already uses locks internally. The Hikari pool (20) stays the real concurrency limit: do not raise it because threads are cheap.
7. **Testcontainers 2** still talks to the sandbox daemon (`DOCKER_MIN_API_VERSION=1.24`).

## 5. Not decided here

k6 p95 comparison, `jdk.tracePinnedThreads` under staging load and the 24 h canary need the staging cluster (Needs external action, see the PLT-032 report).
Boot 4.1 OSS support ends 2027-07: plan the move to the next line before then.
