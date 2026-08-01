# syntax=docker/dockerfile:1.7
#
# Production image for ERPCLEAN2: Angular SPA bundled inside the Spring Boot jar,
# served by the embedded Tomcat on port 8081. NO in-container PostgreSQL, NO supervisord.
# PostgreSQL runs as a separate service (see docker-compose.yml in this directory).
#
# Build context = repo root:
#   docker build -f infra/prod/Dockerfile -t erpclean2:prod .
#
# Stages:
#   1. web-build   — compile the Angular SPA (node:20-alpine)
#   2. api-build   — compile the Spring Boot fat jar (maven:3.9-eclipse-temurin-21)
#   3. runtime     — slim JRE only; no build tools (eclipse-temurin:21-jre-alpine)
#
# The Angular bundle is injected into Spring Boot's static resource path so it is
# served from the same origin as the API — no separate nginx / CDN needed at this
# topology tier. The route /actuator/health/readiness is used for the HEALTHCHECK.

# ---------- Stage 1: build the Angular SPA ----------
FROM node:20-alpine AS web-build
WORKDIR /web
# Copy package.json first so node_modules is cached when only source changes.
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
# `ng build` defaults to the "production" configuration (outputHashing, tree-shaking, etc).
RUN npm run build
# Output: /web/dist/web/browser/

# ---------- Stage 2: build the Spring Boot fat jar ----------
FROM maven:3.9-eclipse-temurin-21 AS api-build
WORKDIR /api
# Dependency layer — only rebuilt when pom.xml changes.
COPY backend/pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY backend/src ./src
# Inject the Angular bundle so it ships inside the jar's static resources.
# Spring Boot serves everything under /static/ at the context root, so the SPA
# is available at / alongside the /api/* and /actuator/* endpoints.
COPY --from=web-build /web/dist/web/browser/ ./src/main/resources/static/
# Build without tests (tests are run in CI before this image is built).
# spring-boot-devtools is scoped to optional/provided in the pom — excluded from
# the repackaged jar automatically; no explicit exclusion needed here.
RUN mvn -B -q -Dmaven.test.skip=true package \
 && cp target/erp-api-*.jar /api/app.jar

# ---------- Stage 3: production runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# JVM tuning: use up to 75 % of cgroup memory limit; faster SecureRandom seeding.
# These are defaults that the operator can override via JAVA_OPTS in the compose file
# or at `docker run` time.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# API port — must match application.yml server.port (${ERP_API_PORT:8081}).
# The operator can override with ERP_API_PORT in the env file, but 8081 is canonical.
ENV ERP_API_PORT=8081

# Management port — actuator lives HERE, not on the API port (application.yml sets
# management.server.port to ${ERP_MANAGEMENT_PORT:9090}). Needed by the HEALTHCHECK below.
ENV ERP_MANAGEMENT_PORT=9090

# No ERP_BOOTSTRAP_ENABLED here — bootstrap is an operator decision at deploy time,
# wired via the .env file.  Baking it true into the image would bootstrap every
# container started from this image regardless of context.
#
# No ERP_JWT_SIGNING_MODE here — operators set signing-mode=file + key paths in .env.

COPY --from=api-build /api/app.jar app.jar

EXPOSE 8081

# Readiness probe: Spring Boot actuator readiness (requires SPRING_PROFILES_ACTIVE=prod
# so management.endpoint.health.probes.enabled=true is active — see application-prod.yml).
#
# NOTE the port. Actuator is served on ERP_MANAGEMENT_PORT (9090), NOT on the API port —
# application.yml moves every actuator endpoint there so Prometheus is never reachable from
# the public 8081 edge. This probe used to target ${ERP_API_PORT}, where the path 404s
# (SpaWebConfig deliberately declines to serve /actuator/** as an SPA route), so wget exited
# non-zero and the container permanently reported (unhealthy) while serving traffic fine.
#
# start-period covers Flyway applying its migrations on a first boot against an empty DB.
HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=5 \
  CMD wget -qO- http://127.0.0.1:${ERP_MANAGEMENT_PORT}/actuator/health/readiness >/dev/null || exit 1

# exec form — PID 1 is java, receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
