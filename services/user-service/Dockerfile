# Use a lightweight JRE for the runtime
FROM eclipse-temurin:25-jre-alpine

# Define which service folder to look into (passed from docker-compose)
ARG SERVICE_PATH

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app

# Copy the pre-built JAR from the specific service's target folder
# Note: Since context is ".", we point to the module folder
COPY ${SERVICE_PATH}/target/*.jar app.jar

# Simple logic to ensure we don't have the .original jar
RUN if [ -f *original* ]; then rm *original*; fi

# Standard JVM optimizations for containers
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]