# syntax=docker/dockerfile:1
# Use repository root as Docker build context (e.g. Render: Dockerfile path = Dockerfile, context = .).
# For local builds from backend/ only, use backend/Dockerfile instead.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
COPY backend/src ./src
RUN chmod +x gradlew \
    && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
