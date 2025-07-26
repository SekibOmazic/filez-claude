package io.filemanager.filez.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories
public class R2dbcConfiguration {
    // Removed custom converters for now to avoid compatibility issues
    // FileStatus enum should work with default R2DBC enum handling
}