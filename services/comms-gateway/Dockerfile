# COM-007: build context = this directory, after `mvn -f services/comms-gateway/pom.xml package` (tests run in CI, not here).
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /build
COPY target/comms-gateway-0.1.0.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER 10001:10001
EXPOSE 3809
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
