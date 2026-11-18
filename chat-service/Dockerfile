# Build context must be the repo root (render.yaml sets dockerContext: .).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q -pl chat-service package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/chat-service/target/chat-service.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
