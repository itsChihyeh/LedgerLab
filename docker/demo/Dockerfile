# The demo app image: the Spring Boot jar built on the host (target/), with the Vite build of frontend/ added
# to its static/ resources so the app serves the page itself and no Node process runs (section 1).
# Build context is the repository root; Dockerfile.dockerignore keeps it small.
FROM node:24 AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS jar
COPY target/ledgerlab-0.0.1-SNAPSHOT.jar /build/app.jar
COPY --from=web /web/dist/ /build/stage/BOOT-INF/classes/static/
RUN jar --update --file /build/app.jar -C /build/stage BOOT-INF/classes/static

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=jar /build/app.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
