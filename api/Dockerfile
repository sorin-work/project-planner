FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Resolve dependencies in their own layer so code changes don't re-download them.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
# Quarkus fast-jar layout: libraries first, they change least often.
COPY --from=build --chown=app:app /app/target/quarkus-app/lib/ lib/
COPY --from=build --chown=app:app /app/target/quarkus-app/*.jar ./
COPY --from=build --chown=app:app /app/target/quarkus-app/app/ app/
COPY --from=build --chown=app:app /app/target/quarkus-app/quarkus/ quarkus/
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD wget -qO- http://127.0.0.1:8080/q/health/ready || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "quarkus-run.jar"]
