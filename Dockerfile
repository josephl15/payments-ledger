# syntax=docker/dockerfile:1

# ---- Build stage: compile the application into one runnable jar ----
# The "-noble" suffix pins Ubuntu 24.04. The untagged "21-jdk" / "21-jre" tags now resolve to Ubuntu 26.04,
# so an unpinned tag would silently change the base image under us.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# Copy the Gradle wrapper and build scripts first. The wrapper inside the image is the single source of the
# Gradle version, so the image builds with exactly the Gradle the project pins (no Gradle install in the image).
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Belt and braces beside the executable bit stored in git: Windows checkouts can lose it.
RUN chmod +x gradlew

# Copy the sources and build the jar. "-x test" skips the tests because Testcontainers cannot start
# containers inside "docker build"; the tests run in CI (and locally) instead.
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# ---- Runtime stage: JRE only, no compiler, no sources, no Gradle ----
FROM eclipse-temurin:21-jre-noble

# Run as an unprivileged user so a compromised process cannot act as root inside the container.
RUN useradd --system --uid 10001 --no-create-home ledger

WORKDIR /app
# Only the jar crosses from the build stage; the jar name comes from archiveFileName in build.gradle.kts.
COPY --from=build /workspace/build/libs/app.jar app.jar

USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
