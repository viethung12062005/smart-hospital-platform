# syntax=docker/dockerfile:1
# Build context = gốc repo:  docker build -f services/audit/Dockerfile .
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl services/audit -am package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/services/audit/target/app.jar /app/app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
