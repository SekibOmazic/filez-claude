package io.filemanager.filez.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import org.postgresql.ds.PGSimpleDataSource;

/**
 * Manual Flyway configuration for R2DBC + PostgreSQL setup.
 * This is needed because Flyway requires JDBC while the app uses R2DBC.
 */
@Component
public class ManualFlywayMigration implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ManualFlywayMigration.class);

    private final Environment environment;

    public ManualFlywayMigration(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        logger.info("=== MANUAL FLYWAY MIGRATION ===");

        try {
            // Get R2DBC connection details and convert to JDBC
            String r2dbcUrl = environment.getProperty("spring.r2dbc.url", "r2dbc:postgresql://localhost:5432/filemanager");
            String username = environment.getProperty("spring.r2dbc.username", "filemanager");
            String password = environment.getProperty("spring.r2dbc.password", "filemanager");

            // Convert R2DBC URL to JDBC URL
            String jdbcUrl = r2dbcUrl.replace("r2dbc:", "jdbc:");

            logger.info("R2DBC URL: {}", r2dbcUrl);
            logger.info("JDBC URL for Flyway: {}", jdbcUrl);
            logger.info("Username: {}", username);

            // Create JDBC DataSource for Flyway manually
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(jdbcUrl);
            dataSource.setUser(username);
            dataSource.setPassword(password);

            // Configure and run Flyway
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .validateOnMigrate(true)
                    .load();

            logger.info("Starting Flyway migration...");

            // Check current status
            var info = flyway.info();
            logger.info("Current migration status:");
            logger.info("  - Applied migrations: {}", info.applied().length);
            logger.info("  - Pending migrations: {}", info.pending().length);

            for (var migration : info.applied()) {
                logger.info("  ✅ Applied: {} - {}", migration.getVersion(), migration.getDescription());
            }

            for (var migration : info.pending()) {
                logger.info("  ⏳ Pending: {} - {}", migration.getVersion(), migration.getDescription());
            }

            if (info.pending().length > 0) {
                logger.info("Running {} pending migrations...", info.pending().length);
                var result = flyway.migrate();
                logger.info("✅ Migration completed successfully!");
                logger.info("  - Migrations executed: {}", result.migrationsExecuted);
                logger.info("  - Target schema version: {}", result.targetSchemaVersion);

                if (result.warnings != null && !result.warnings.isEmpty()) {
                    logger.warn("Migration warnings: {}", result.warnings);
                }
            } else {
                logger.info("✅ All migrations are already applied");
            }

            // Verify tables were created
            try (var connection = dataSource.getConnection();
                 var stmt = connection.createStatement()) {

                var rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'files'");
                if (rs.next() && rs.getInt(1) > 0) {
                    logger.info("✅ Files table exists - migrations successful!");
                } else {
                    logger.error("❌ Files table still doesn't exist after migration!");
                }
            }

        } catch (Exception e) {
            logger.error("❌ Manual Flyway migration failed: {}", e.getMessage(), e);
            throw new RuntimeException("Database migration failed", e);
        }

        logger.info("=== MANUAL FLYWAY MIGRATION COMPLETE ===");
    }
}