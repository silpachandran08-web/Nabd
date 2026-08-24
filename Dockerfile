# Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Copy backend source and build
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn clean package -DskipTests

# Copy migrations into the build image
COPY db/migrations /app/db/migrations

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy JAR and migrations from builder
COPY --from=builder /app/target/hms-backend-0.1.0.jar app.jar
COPY --from=builder /app/db/migrations /app/db/migrations

# Point Flyway to the migrations inside the container
ENV SPRING_FLYWAY_LOCATIONS=filesystem:/app/db/migrations

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
