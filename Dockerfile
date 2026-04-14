# syntax=docker/dockerfile:1.6
# Stage 1: build the fat jar with Gradle
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# --configure-on-demand keeps Gradle from configuring :game-app:game-headed
# (which runs `git rev-list --count HEAD` at configuration time) since the
# sidecar doesn't depend on it. BuildKit cache mounts persist Gradle's
# user home and project cache across builds on the same host.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/.gradle \
    ./gradlew :game-app:ai-sidecar:installDist \
        --no-daemon --parallel --configure-on-demand

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/game-app/ai-sidecar/build/install/ai-sidecar /app
ENV SIDECAR_BIND_HOST=0.0.0.0
ENV SIDECAR_PORT=8099
EXPOSE 8099
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8099/health || exit 1
ENTRYPOINT ["/app/bin/ai-sidecar"]
