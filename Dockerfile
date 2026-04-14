# Stage 1: build the fat jar with Gradle
FROM eclipse-temurin:21-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
# game-app/game-headed/build.gradle runs `git rev-list --count HEAD` at configuration time.
# A fresh one-commit repo is enough to satisfy that call without bloating the build context.
RUN git init -q \
 && git -c user.email=build@local -c user.name=build add -A \
 && git -c user.email=build@local -c user.name=build commit -q -m build
RUN ./gradlew :game-app:ai-sidecar:installDist --no-daemon

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
