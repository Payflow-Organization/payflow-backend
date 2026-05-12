# Builder
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw

RUN --mount=type=cache,target=/root/.m2,id=maven \ ./mvnw dependency:go-offline -B
COPY src src

RUN --mount=type=cache,target=/root/.m2,id=maven \ ./mvnw clean package -DskipTests -B

#Runtime
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/payflow-*.jar app.jar
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health

EXPOSE 8080

ENTRYPOINT ["java", \
    "-Dspring.profiles.active=prod", \
    "-XX:+UseG1GC", \
    "-XX:InitialRAMPercentage=50.0", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseStringDeduplication", \
    "-jar", "app.jar"]

