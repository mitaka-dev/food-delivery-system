FROM eclipse-temurin:25-jre-alpine

ARG SERVICE_PATH

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app

COPY ${SERVICE_PATH}/target/*.jar app.jar

RUN if [ -f *original* ]; then rm *original*; fi

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
