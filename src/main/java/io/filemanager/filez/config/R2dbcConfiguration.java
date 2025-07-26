package io.filemanager.filez.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories
public class R2dbcConfiguration extends AbstractR2dbcConfiguration {

    @Override
    public ConnectionFactory connectionFactory() {
        // This will be auto-configured by Spring Boot
        return null;
    }

    // No custom converters needed - R2DBC will handle String <-> FileStatus enum automatically
    // since FileStatus is a simple enum and the database column is now VARCHAR
}