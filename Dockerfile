# ── Stage 1: Build ────────────────────────────────────────
# Use official Gradle image with JDK 21 pre-installed (no Gradle download needed)
FROM gradle:8.14-jdk21 AS builder

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon || true

COPY src/ src/
RUN gradle bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
