# OPS-031: the jar is built and tested by the CI pipeline (mvn verify), then packaged here once.
# Build: mvn -f backend/pom.xml -B verify && docker build -t nabd-hms .
FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY backend/target/hms-backend-0.1.0.jar app.jar
COPY db/migrations /app/db/migrations
ENV SPRING_FLYWAY_LOCATIONS=filesystem:/app/db/migrations
EXPOSE 8080
USER nonroot
# distroless java entrypoint is ["java","-jar"]
CMD ["app.jar"]
