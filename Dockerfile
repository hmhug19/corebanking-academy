# syntax=docker/dockerfile:1
FROM maven:3-eclipse-temurin-21-noble AS build
WORKDIR /build

COPY pom.xml lombok.config ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline

COPY src ./src
# The SQL scripts are never auto-executed; the persistence tests load them into a throwaway database when the build
# can reach a Docker daemon, and are skipped when it cannot.
COPY docs/sql ./docs/sql
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean verify

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
RUN mkdir -p /app/logs /app/data/knowledge && chown -R 10001:10001 /app/logs /app/data
COPY --from=build --chown=10001:10001 /build/target/corebanking-academy-*.jar ./app.jar

ENV SERVER_PORT=8019
USER 10001:10001
EXPOSE 8019

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8019/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
