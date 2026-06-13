# Multi-stage build for the ERP API.
# Stage 1: build the jar with Maven (cached deps layer). Stage 2: slim JRE runtime.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first — only re-downloads when pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/erp-api-*.jar app.jar
# D-4: API listens on 8081 (ERP_API_PORT default). 8080 was wrong — fixed here.
EXPOSE 8081
# D-4: Healthcheck against the readiness probe (DB included).
# Actuator runs on the management port (ERP_MANAGEMENT_PORT default 9090, set in application.yml D-5).
# eclipse-temurin:21-jre-alpine ships wget but NOT curl — use wget -q -O-.
# Fail fast: 5-second timeout, start-period of 60s for Flyway migration, 3 retries before UNHEALTHY.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:9090/actuator/health/readiness || exit 1
# Profile and DB connection come from the environment (compose / deploy --env-file).
ENTRYPOINT ["java", "-jar", "app.jar"]
