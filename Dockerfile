# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy Maven configuration and source
COPY pom.xml .
COPY .mvn .mvn
ARG MAVEN_CENTRAL_URL=https://maven-central.storage-download.googleapis.com/maven2/
COPY src src
ARG VERSION=unknown
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -Dmaven.central.url="$MAVEN_CENTRAL_URL" package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -g 1000 -S sddp && adduser -u 1000 -S sddp -G sddp

# Copy built jar
COPY --from=build /app/target/*.jar app.jar

# Copy migration scripts for Flyway (if needed at runtime)
COPY --from=build /app/src/main/resources/db/migration ./db/migration

# OCI image labels
ARG VERSION=unknown
ARG GITHUB_REPOSITORY=unknown/unknown
LABEL org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.title="SDDP Core Backend" \
      org.opencontainers.image.source="https://github.com/${GITHUB_REPOSITORY}" \
      org.opencontainers.image.documentation="https://github.com/${GITHUB_REPOSITORY}#readme" \
      org.opencontainers.image.licenses="MIT"

USER sddp

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=docker \
    SddP_DB_URL=jdbc:postgresql://postgres:5432/sddp \
    SddP_DB_USERNAME=sddp \
    SddP_DB_PASSWORD=sddp

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
