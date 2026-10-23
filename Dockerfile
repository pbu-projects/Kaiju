# Multi-stage container build for Kaiju using Java 25 LTS and Micronaut AOT

# Stage 1: Build stage with JDK 25
FROM eclipse-temurin:25-jdk AS build
WORKDIR /home/app

# Copy build configuration and Gradle wrapper
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./

# Pre-fetch Gradle dependencies for layer caching
RUN ./gradlew dependencies --no-daemon || true

# Copy application source code and OpenAPI documentation
COPY src src
COPY docs docs

# Build Ahead-of-Time (AOT) optimized executable JAR
RUN ./gradlew optimizedJitJarAll --no-daemon

# Stage 2: Production runtime stage with JRE 25
FROM eclipse-temurin:25-jre
WORKDIR /home/app

# Create unprivileged system user and group (UID 10001:10001)
RUN groupadd -g 10001 kaiju && \
    useradd -u 10001 -g kaiju -m -s /bin/false kaiju

# Copy AOT-optimized application binary from build stage
COPY --from=build --chown=kaiju:kaiju /home/app/build/libs/kaiju-*-all-optimized.jar /home/app/application.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/home/app/application.jar"]
