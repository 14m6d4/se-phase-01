# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
# Dependencies live in this layer (not a cache mount) so the CI layer cache keeps them until pom.xml changes.
RUN mvn -B -q dependency:go-offline
COPY src src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build.
RUN mvn -B -q package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
# Pick up Alpine security fixes released after the base image was built (DEP1: openssl, zlib). A cached layer
# keeps the packages of the build that made it, so rebuild without cache (or rescan with Trivy) to pick up later ones.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /src/target/cinema-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
