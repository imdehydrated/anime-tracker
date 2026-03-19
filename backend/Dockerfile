# Multi-stage build: Stage 1 = Build, Stage 2 = Run
# Why? Build stage needs Maven and source code. Run stage only needs Java and the JAR.
# This keeps the final image small (no Maven, no source code, just the compiled app).

# === STAGE 1: BUILD ===
# Use Maven image with Java 17 to build the application
FROM maven:3.9-eclipse-temurin-17 AS build

# Set working directory inside the container
# All subsequent commands run from this directory
WORKDIR /app

# Copy pom.xml first (before source code)
# Why? Docker caches layers. If pom.xml doesn't change, Docker reuses the cached dependencies.
# This makes builds much faster!
COPY pom.xml .

# Download all dependencies specified in pom.xml
# This layer is cached unless pom.xml changes
RUN mvn dependency:go-offline -B

# Now copy the source code
COPY src ./src

# Build the application
# "package" compiles code, runs tests, and creates a JAR file in target/
# "-DskipTests" skips tests (for faster builds; remove this in production!)
RUN mvn package -DskipTests

# === STAGE 2: RUN ===
# Use a slim Java 17 runtime (no Maven, no build tools).
# Pin Alpine 3.23 so the build can pick up patched runtime packages like
# libexpat 2.7.5-r0 from the current 3.23 repositories during apk upgrade.
FROM eclipse-temurin:17-jre-alpine-3.23

# Set working directory
WORKDIR /app

# Pull the latest patched OS packages at build time to reduce runtime CVEs.
RUN apk upgrade --no-cache

# Copy only the JAR file from the build stage
# --from=build references the "build" stage above
# This is why we use multi-stage builds: final image only has the JAR, not the source code or Maven
COPY --from=build /app/target/*.jar app.jar

# Expose port 8080
# This is documentation - it tells Docker "this app listens on port 8080"
# (You still need to map ports in docker-compose.yml)
EXPOSE 8080

# Run the application
# ENTRYPOINT is the command that runs when the container starts
# "java -jar app.jar" starts Spring Boot
ENTRYPOINT ["java", "-jar", "app.jar"]
