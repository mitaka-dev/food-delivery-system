# Stage 1: runtime image only — JAR is pre-built by docker-compose (or CI)
# Uses Amazon Corretto 25 headless (AL2023) as the distroless-equivalent runtime
FROM amazoncorretto:25-al2023

ARG SERVICE_PATH

WORKDIR /app

COPY ${SERVICE_PATH}/target/*.jar app.jar

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]