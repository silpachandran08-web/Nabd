# OPS-025: layered, non-root, distroless. Tests are NOT run here: CI stages 2 and 3 run them, stage 6 packages the jar
# once (mvn -f backend/pom.xml package) and this file only wraps it. Local: mvn -f backend/pom.xml package && docker build .
#   docker build .                  -> the API image (last stage, the default target)
#   docker build --target migrate . -> the Flyway migrate job image
ARG JAVA_RUNTIME=gcr.io/distroless/java21-debian12:nonroot

FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /build
COPY backend/target/hms-backend-0.1.0.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Shared by both images; dependencies change least, application most (cache order).
FROM ${JAVA_RUNTIME} AS base
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
# numeric UID: distroless has no passwd entry for it and no shell either
USER 10001:10001

# Migrate job (OPS-003/OPS-025): Flyway as nabd_migrator, run to completion BEFORE the rollout. MIGRATE_DB_URL,
# MIGRATE_DB_USER and MIGRATE_DB_PASSWORD are set only on this job. Roles: infra/db/bootstrap-roles.sql (once per database).
FROM base AS migrate
COPY db/migrations /app/db/migrations
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "app.jar:lib/*", "com.nabd.hms.common.MigrateMain"]

# API image: Flyway off (application.yml), actuator on 8081 (never routed by the ingress), OpenTelemetry agent for traces.
# Keep the agent version equal to opentelemetry-instrumentation-annotations in backend/pom.xml.
FROM base AS app
ADD --chmod=444 --checksum=sha256:fa039f86082b559e53283a863e03de5df7db4bdb4840c5cbb8bf38f292987d5b \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.9.0/opentelemetry-javaagent.jar /otel/agent.jar
# OTEL_EXPORTER_OTLP_ENDPOINT is set per environment; metrics go through Prometheus, so the agent exports traces only.
ENV OTEL_SERVICE_NAME=hms-backend OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-javaagent:/otel/agent.jar", "-jar", "app.jar"]
