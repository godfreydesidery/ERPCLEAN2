# syntax=docker/dockerfile:1.7
#
# Single-container QA image for ERPCLEAN2: Angular bundle + Spring Boot API +
# PostgreSQL, all running together under supervisord. Build context = repo root.
#
# Wrong shape for production (coupled lifecycle, no scaling) — right shape for
# QA: one image, one `docker run`, one volume.
#
# Usage (on the server):
#   docker build -f infra/qa/Dockerfile -t erpclean2:qa .
#   docker volume create erpclean2-data
#   docker run -d --name erpclean2 -p 80:8081 \
#     -v erpclean2-data:/var/lib/postgresql/data \
#     --env-file infra/qa/qa.env --restart unless-stopped erpclean2:qa
#
# Boot takes ~30s (Postgres init on first run, then Flyway V1..V5, then JVM start).

# ---------- Stage 1: build the Angular bundle ----------
FROM node:20-alpine AS web-build
WORKDIR /web
COPY web/package.json ./
# package-lock.json may be gitignored on the box; npm install resolves from package.json.
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN npm run build
# Output: /web/dist/web/browser/ (Angular application builder).

# ---------- Stage 2: build the Spring Boot fat jar ----------
FROM maven:3.9-eclipse-temurin-21 AS api-build
WORKDIR /api
COPY backend/pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY backend/src ./src
# Drop the Angular bundle into Spring Boot's static path so it ships inside the jar.
COPY --from=web-build /web/dist/web/browser/ ./src/main/resources/static/
RUN mvn -B -q -Dmaven.test.skip=true package \
 && cp target/erp-api-*.jar /api/app.jar

# ---------- Stage 3: runtime ----------
FROM eclipse-temurin:21-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive
# PostgreSQL 15 from the Ubuntu jammy repos (postgresql-15 via PGDG would pin the
# exact version; jammy's default postgresql is 14 — install the versioned package
# from PGDG to match the dev/Testcontainers Postgres 15).
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg lsb-release \
 && install -d /usr/share/postgresql-common/pgdg \
 && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
 && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] http://apt.postgresql.org/pub/repos/apt jammy-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends postgresql-15 supervisor tini \
 && rm -rf /var/lib/apt/lists/* \
 && rm -rf /var/lib/postgresql/15/main

# Supervisor process map (postgres + api).
COPY infra/qa/supervisord.conf /etc/supervisor/supervisord.conf

# First-run DB bootstrap + handoff to supervisord.
COPY infra/qa/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# The Spring Boot fat jar.
COPY --from=api-build /api/app.jar /opt/erpclean2/app.jar

# QA Spring config (logging + Hikari). Datasource points at the local Postgres
# via ERP_DB_URL set in the entrypoint.
COPY infra/qa/application-qa.yml /opt/erpclean2/application-qa.yml
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/opt/erpclean2/application-qa.yml \
    SPRING_PROFILES_ACTIVE=qa \
    ERP_JWT_SIGNING_MODE=dev-in-memory

VOLUME ["/var/lib/postgresql/data"]
EXPOSE 8081

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
