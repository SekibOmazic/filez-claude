# Multi-stage build for optimal image size
FROM gradle:8-jdk21-alpine AS build

# Set working directory
WORKDIR /app

# Copy gradle files for dependency caching
COPY build.gradle settings.gradle ./
COPY gradle gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon

# Copy source code
COPY src src

# Build application
RUN gradle build --no-daemon -x test

# Runtime stage
FROM openjdk:21-jdk-alpine

# Install curl for health checks
RUN apk add --no-cache curl

# Create app user
RUN addgroup -g 1001 -S filez && \
    adduser -u 1001 -S filez -G filez

# Set working directory
WORKDIR /app

# Copy built jar from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Change ownership
RUN chown -R filez:filez /app

# Switch to non-root user
USER filez

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]