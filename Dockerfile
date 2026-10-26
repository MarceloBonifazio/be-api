# syntax=docker/dockerfile:1
# Dockerfile ÚNICO, dois targets:
#   target: dev     → hot-reload, source por bind-mount
#   target: runner  → imagem de produção, non-root, só artefatos
#
# O compose escolhe o estágio pelo `target:` (service.yml → dev, service.prod.yml → runner) e o CI
# builda `--target runner`.
#
# Substituiu o par Dockerfile.dev/Dockerfile.prod: os dois divergiam em silêncio — uma correção
# aplicada num não chegava no outro (foi assim que um COPY de arquivo inexistente sobreviveu em
# 18 .prod).

# ═════════ dev ═════════
# DEV (be-api · Spring Boot Java, scaffold oficial) — `./gradlew bootRun`. Source por bind-mount.
# O app foi gerado pelo Spring Initializr (Gradle/Java): gradlew presente, deps NAO baixadas.
# Usamos o gradle do sistema (imagem gradle:8-jdk21) em vez do wrapper para evitar o download do wrapper.
# gradle:8-jdk21 e Debian e NAO tem cliente HTTP -> instalamos curl para o fallback de token do vault.
FROM gradle:8-jdk21 AS dev
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
RUN chmod +x gradlew
ENV PORT=4000
EXPOSE 4000
ENTRYPOINT ["sh", "/app/scripts/vault-entrypoint.sh"]
CMD ["sh", "-c", "./gradlew bootRun --args=\"--server.port=$PORT\""]

# ═════════ produção ═════════
# PROD (be-api · Spring Boot Java) — jar bootJar executado com java -jar. Multi-stage.
# Gerado pelo Spring Initializr (Gradle/Java): gradlew presente, deps NAO baixadas.
# Stage runner e eclipse-temurin:21-jre-alpine (Debian, sem cliente HTTP) -> instalamos curl para o fallback do vault.
# Os arquivos do vault (vault-entrypoint.sh, vault-load.*) sao copiados explicitamente para /app no runner,
# pois o `COPY . .` so ocorre no stage de build.

# --- build stage ---
FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# --- runner stage ---
FROM eclipse-temurin:21-jre-alpine AS runner
RUN (addgroup -S app 2>/dev/null || groupadd -r app 2>/dev/null || true) \
    && (adduser -S -G app app 2>/dev/null || useradd -r -g app app 2>/dev/null || true)
WORKDIR /app
# Só o helper que ESTA linguagem realmente ganha: o gerador emite vault-load.mjs para node/frontend
# e vault-load.py para python, e mais nada. Copiar os dois fazia o build do runner falhar com
# "vault-load.py: not found" — o entrypoint cai nos fallbacks de curl/wget quando não há helper.
COPY --chown=app:app scripts/vault-entrypoint.sh /app/scripts/
COPY --chown=app:app --from=build /app/build/libs/*.jar app.jar
# openapi.json (contrato) → WORKDIR p/ ser servido em /openapi.json + /docs (lido em runtime).
COPY --chown=app:app --from=build /app/openapi.json /app/openapi.json
# docs.html (UI estática do contrato) → WORKDIR p/ ser servido em /docs (lido em runtime).
COPY --chown=app:app --from=build /app/docs.html /app/docs.html
ENV PORT=4000
EXPOSE 4000
USER app
# Metadados de build p/ /version (CI: docker build --build-arg GIT_COMMIT=... GIT_BRANCH=... BUILD_TIME=...).
ARG GIT_COMMIT=unknown
ARG GIT_BRANCH=unknown
ARG BUILD_TIME=unknown
ENV GIT_COMMIT=${GIT_COMMIT} GIT_BRANCH=${GIT_BRANCH} BUILD_TIME=${BUILD_TIME}
ENTRYPOINT ["sh", "/app/scripts/vault-entrypoint.sh"]
CMD ["sh", "-c", "java -jar app.jar --server.port=$PORT"]
