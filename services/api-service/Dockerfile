# Phase 22 — api-service image.
#
# Build context is the repository root (see infrastructure/docker/docker-compose.yml),
# so that the already-committed OpenTelemetry agent at the repo root can be
# copied in. No new agent jar is downloaded or committed.
#
# Build stage: Maven + JDK 21. Tests are skipped here on purpose; they run in
# CI (Phase 23), not on every image build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar
COPY services/api-service/pom.xml ./pom.xml
COPY services/api-service/src ./src
RUN mvn -B -DskipTests package

# Runtime stage: slim JRE 21, non-root.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/*.jar ./app.jar
COPY --from=build /app/opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar
RUN chown -R appuser:appgroup /app
USER appuser
# Attach the OpenTelemetry agent (version 2.31.1, per the jar manifest) to
# every JVM start. The exporter endpoint and service name come from the
# environment (see docker-compose.yml); the agent defaults apply otherwise.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
