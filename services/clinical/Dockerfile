# syntax=docker/dockerfile:1
# Build context = gốc repo:  docker build -f services/clinical/Dockerfile .
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl services/clinical -am package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

FROM gcr.io/distroless/java21-debian13:nonroot
WORKDIR /app
COPY --from=build /src/services/clinical/target/app.jar /app/app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
