# ACC-039: the Edge image. Build context is this directory (docker build -f Dockerfile .), so nothing of Core can enter it.
# Tests are not run here: the patient-access-edge workflow runs them, then packages the jar once (mvn -f pom.xml package).
ARG JAVA_RUNTIME=gcr.io/distroless/java21-debian12:nonroot

FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /build
COPY target/patient-access-edge-0.1.0.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM ${JAVA_RUNTIME}
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER 10001:10001
# 8080 public (/healthz only); 8081 metrics and health, never routed by the ingress.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
