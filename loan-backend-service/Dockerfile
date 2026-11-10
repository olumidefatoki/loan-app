FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache curl \
    && addgroup -S lending \
    && adduser -S lending -G lending
WORKDIR /app
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
COPY --from=build --chown=lending:lending /src/target/loan-backend-service.jar /app/app.jar
USER lending
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=production \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["/entrypoint.sh"]
