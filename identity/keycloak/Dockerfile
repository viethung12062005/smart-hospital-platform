# syntax=docker/dockerfile:1
# Keycloak with the SHP login theme and extensions (IAM-03, ADR-0017). Build context = repo root:
#   docker build -f identity/keycloak/Dockerfile .
# The base is the Keycloak version every environment runs (docs/04-devops/keycloak.md), pinned by the digest of its latest
# rebuild (26.8.2-3, operating-system fixes). The provider JAR is only copied: `start` (cluster) and `start-dev` (local) run the
# Quarkus build themselves, as they do with the stock image.
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl identity/keycloak -am package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

FROM quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3
COPY --from=build /src/identity/keycloak/target/shp-keycloak.jar /opt/keycloak/providers/shp-keycloak.jar
