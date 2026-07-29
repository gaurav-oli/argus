# syntax=docker/dockerfile:1
# Argus backend — Spring Boot 4 / Java 25. Multi-stage: build with the project's
# Maven wrapper (pins Maven + JDK 25), run on a slim JRE. arm64-native on the Mini.

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Resolve dependencies first so this layer caches unless the POM changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Build the executable boot jar (tests run in CI / locally, not in the image build).
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# pg_dump for on-demand backups ("Back Up Now"): Ubuntu's default repo on this base image already
# ships postgresql-client 18, matching the pgvector/pgvector:0.8.2-pg18 server exactly — no need for
# the PGDG apt repo. Connects over TCP to the postgres compose service (see BackupTriggerService),
# not via docker-exec, so the backend image needs its own pg_dump binary.
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN groupadd --system argus && useradd --system --gid argus --no-create-home argus

# Single boot jar (layered extraction is a future caching optimization).
COPY --from=build /app/target/argus-backend-*.jar app.jar

USER argus
EXPOSE 8080

# Profile + connection details are injected by docker-compose at runtime
# (SPRING_PROFILES_ACTIVE=prod, datasource/redis hosts, OLLAMA_BASE_URL).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
