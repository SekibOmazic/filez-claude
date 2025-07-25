package io.filemanager.filez.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.concurrent.CompletableFuture;

/**
 * Configuration for local development with manual service management.
 * This activates when 'local' profile is used WITHOUT Testcontainers.
 *
 * Usage: --spring.profiles.active=local
 *
 * Requires:
 * - Manual PostgreSQL setup
 * - Manual S3Mock setup
 * - Manual AVScan mock setup
 */
@Configuration
@Profile("local")
public class LocalDevelopmentConfiguration {

    /**
     * Instructions for manual setup when this profile is active.
     * This will print helpful startup instructions.
     */
    @Bean
    @ConditionalOnProperty(name = "testcontainers.enabled", havingValue = "false", matchIfMissing = true)
    public String localSetupInstructions(Environment env) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // Wait for Spring Boot banner
                System.out.println("\n" + "=".repeat(80));
                System.out.println("🚀 LOCAL DEVELOPMENT MODE ACTIVATED");
                System.out.println("=".repeat(80));
                System.out.println("To complete setup, start these services manually:");
                System.out.println();
                System.out.println("1. PostgreSQL:");
                System.out.println("   docker run --name postgres -e POSTGRES_DB=filemanager \\");
                System.out.println("     -e POSTGRES_USER=filemanager -e POSTGRES_PASSWORD=filemanager \\");
                System.out.println("     -p 5432:5432 -d postgres:15-alpine");
                System.out.println();
                System.out.println("2. S3 Mock:");
                System.out.println("   docker run --name s3mock -p 9090:9090 \\");
                System.out.println("     -e initialBuckets=filemanager-bucket -d adobe/s3mock:4.6.0");
                System.out.println();
                System.out.println("3. AVScan Mock:");
                System.out.println("   python3 avscan-simple-mock.py");
                System.out.println();
                System.out.println("Application will be available at: http://localhost:8080");
                System.out.println("=".repeat(80) + "\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return "local-setup-instructions";
    }
}