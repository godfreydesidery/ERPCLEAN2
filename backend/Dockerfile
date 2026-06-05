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
EXPOSE 8080
# Profile and DB connection come from the environment (compose / deploy --env-file).
ENTRYPOINT ["java", "-jar", "app.jar"]
