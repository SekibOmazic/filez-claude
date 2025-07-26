package io.filemanager.filez.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories
public class R2dbcConfiguration {
    // For Spring Boot 4.0 with PostgreSQL, enum handling should work out of the box
    // The PostgreSQL R2DBC driver has built-in support for PostgreSQL ENUMs
    // If issues persist, we can add custom converters later
}