# Build context must be the repo root (render.yaml sets dockerContext: .) because
# this is a multi-module Maven reactor and the service depends on the common module.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q -pl common,ingestion-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/ingestion-service/target/ingestion-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
