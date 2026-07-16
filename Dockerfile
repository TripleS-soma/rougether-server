# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY common/build.gradle ./common/build.gradle
COPY domain/build.gradle ./domain/build.gradle
COPY user-api/build.gradle ./user-api/build.gradle
COPY admin-api/build.gradle ./admin-api/build.gradle
COPY batch/build.gradle ./batch/build.gradle

ARG APP_MODULE=user-api

COPY . .

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon ":${APP_MODULE}:bootJar"

FROM eclipse-temurin:25-jre

ARG APP_MODULE=user-api

WORKDIR /app

COPY --from=build /workspace/${APP_MODULE}/build/libs/*.jar /app/app.jar

EXPOSE 8080 8081 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
