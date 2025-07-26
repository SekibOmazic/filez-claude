package io.filemanager.filez.config;

import io.filemanager.filez.model.FileStatus;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableR2dbcRepositories
public class R2dbcConfiguration extends AbstractR2dbcConfiguration {

    @Override
    public ConnectionFactory connectionFactory() {
        // This will be auto-configured by Spring Boot
        return null;
    }

    @Bean
    @Override
    public R2dbcCustomConversions r2dbcCustomConversions() {
        List<Converter<?, ?>> converters = new ArrayList<>();
        converters.add(new FileStatusReadingConverter());
        converters.add(new FileStatusWritingConverter());
        return new R2dbcCustomConversions(getStoreConversions(), converters);
    }

    @WritingConverter
    static class FileStatusWritingConverter implements Converter<FileStatus, String> {
        @Override
        public String convert(FileStatus source) {
            return source.name();
        }
    }

    @ReadingConverter
    static class FileStatusReadingConverter implements Converter<String, FileStatus> {
        @Override
        public FileStatus convert(String source) {
            return FileStatus.valueOf(source);
        }
    }
}