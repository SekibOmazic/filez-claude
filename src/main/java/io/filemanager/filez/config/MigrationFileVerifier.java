package io.filemanager.filez.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Verifies that migration files exist and are readable
 */
@Component
public class MigrationFileVerifier implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(MigrationFileVerifier.class);

    private final ResourceLoader resourceLoader;

    public MigrationFileVerifier(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("=== MIGRATION FILE VERIFICATION ===");

        String[] migrationFiles = {
                "V1__Initial_schema.sql",
                "V2__Fix_nullable_columns.sql",
                "V3__Fix_nullable_columns.sql",
                "V4__Ensure_proper_nullable_columns.sql"
        };

        for (String fileName : migrationFiles) {
            String resourcePath = "classpath:db/migration/" + fileName;
            logger.info("Checking migration file: {}", resourcePath);

            try {
                Resource resource = resourceLoader.getResource(resourcePath);

                if (resource.exists()) {
                    logger.info("  ✅ File exists: {}", fileName);

                    // Read first few lines to verify content
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                        String firstLine = reader.readLine();
                        if (firstLine != null) {
                            logger.info("  📄 First line: {}", firstLine.trim());
                        }

                        // Count lines
                        int lineCount = 1;
                        while (reader.readLine() != null) {
                            lineCount++;
                        }
                        logger.info("  📊 Total lines: {}", lineCount);
                    }
                } else {
                    logger.error("  ❌ File not found: {}", fileName);
                }

            } catch (Exception e) {
                logger.error("  ❌ Error reading file {}: {}", fileName, e.getMessage());
            }
        }

        // Also check if the db/migration directory exists
        try {
            Resource migrationDir = resourceLoader.getResource("classpath:db/migration/");
            if (migrationDir.exists()) {
                logger.info("✅ Migration directory exists: db/migration/");
            } else {
                logger.error("❌ Migration directory not found: db/migration/");
            }
        } catch (Exception e) {
            logger.error("❌ Error checking migration directory: {}", e.getMessage());
        }

        logger.info("=== MIGRATION FILE VERIFICATION COMPLETE ===");
    }
}