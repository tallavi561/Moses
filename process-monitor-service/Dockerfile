# Build context must be the repo root (render.yaml sets dockerContext: .).
# This service doesn't depend on common, but the whole reactor is still copied in
# for a simple, uniform Dockerfile across services.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q -pl process-monitor-service package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/process-monitor-service/target/process-monitor-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
