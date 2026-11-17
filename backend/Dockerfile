# syntax=docker/dockerfile:1
# Multi-stage build: JDK for building, JRE-only runtime image running as a non-root user.
# Tests are not run here (integration tests need Docker); run them with .\mvnw.cmd verify.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system payassist && useradd --system --gid payassist --no-create-home payassist
WORKDIR /app
COPY --from=build /workspace/target/payassist-backend-*.jar app.jar
USER payassist
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
