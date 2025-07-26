package io.filemanager.filez.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
public class R2dbcConfiguration {

    /**
     * Flyway requires a JDBC DataSource (not R2DBC).
     * We create a separate JDBC connection just for migrations.
     */
    @Bean
    public DataSource flywayDataSource(Environment env) {
        String r2dbcUrl = env.getProperty("spring.r2dbc.url");
        String username = env.getProperty("spring.r2dbc.username");
        String password = env.getProperty("spring.r2dbc.password");

        // Convert R2DBC URL to JDBC URL
        String jdbcUrl = r2dbcUrl.replace("r2dbc:postgresql://", "jdbc:postgresql://");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("org.postgresql.Driver");

        return dataSource;
    }

    /**
     * Configure Flyway to use our JDBC DataSource.
     * This will run migrations automatically on startup.
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource flywayDataSource) {
        return Flyway.configure()
                .dataSource(flywayDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
    }
}