package io.filemanager.filez.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        S3Properties s3,
        AvScanProperties avScan,
        CallbackProperties callback,
        UploadProperties upload
) {

    public record S3Properties(
            String endpoint,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            boolean pathStyleAccess
    ) {}

    public record AvScanProperties(
            String endpoint,
            Duration timeout
    ) {}

    public record CallbackProperties(
            String baseUrl
    ) {}

    public record UploadProperties(
            String maxFileSize,
            String allowedTypes,
            Duration tempCleanupInterval
    ) {}
}