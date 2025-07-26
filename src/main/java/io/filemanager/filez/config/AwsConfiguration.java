package io.filemanager.filez.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

@Configuration
public class AwsConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AwsConfiguration.class);

    @Bean
    public S3AsyncClient s3AsyncClient(AppProperties appProperties) {
        var s3Props = appProperties.s3();

        logger.info("=== S3 CLIENT CONFIGURATION ===");
        logger.info("Endpoint: {}", s3Props.endpoint());
        logger.info("Region: {}", s3Props.region());
        logger.info("Bucket: {}", s3Props.bucket());
        logger.info("Access Key: {}", s3Props.accessKey());
        logger.info("Path Style Access: {}", s3Props.pathStyleAccess());

        var credentials = AwsBasicCredentials.create(
                s3Props.accessKey(),
                s3Props.secretKey()
        );

        // Configure HTTP client with settings optimized for S3Mock
        var httpClient = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(50)  // Reduced for S3Mock
                .maxPendingConnectionAcquires(1000)  // Reduced for S3Mock
                .connectionTimeout(Duration.ofSeconds(60))  // Increased timeout
                .readTimeout(Duration.ofSeconds(60))  // Increased timeout
                .writeTimeout(Duration.ofSeconds(60))  // Increased timeout
                .connectionAcquisitionTimeout(Duration.ofSeconds(30))
                .build();

        // S3 configuration optimized for S3Mock
        var s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(s3Props.pathStyleAccess())
                .checksumValidationEnabled(false)  // Disable for S3Mock compatibility
                .chunkedEncodingEnabled(true)  // Enable chunked encoding for streaming
                .accelerateModeEnabled(false)  // Disable acceleration
                .dualstackEnabled(false)  // Disable dualstack
                .build();

        S3AsyncClient client;
        try {
            client = S3AsyncClient.builder()
                    .region(Region.of(s3Props.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(URI.create(s3Props.endpoint()))
                    .serviceConfiguration(s3Config)
                    .httpClient(httpClient)
                    .build();

            logger.info("✅ S3 client created successfully");

            // Test the client configuration
            testS3ClientAsync(client, s3Props);

        } catch (Exception e) {
            logger.error("❌ Failed to create S3 client: {}", e.getMessage(), e);
            throw new RuntimeException("S3 client configuration failed", e);
        }

        return client;
    }

    /**
     * Test S3 client connectivity asynchronously
     */
    private void testS3ClientAsync(S3AsyncClient client, AppProperties.S3Properties s3Props) {
        // Test connectivity in background
        client.listBuckets()
                .thenAccept(response -> {
                    logger.info("✅ S3 connectivity test successful");
                    logger.info("Available buckets: {}", response.buckets().size());

                    boolean bucketExists = response.buckets().stream()
                            .anyMatch(bucket -> bucket.name().equals(s3Props.bucket()));

                    if (bucketExists) {
                        logger.info("✅ Target bucket '{}' exists", s3Props.bucket());
                    } else {
                        logger.warn("⚠️ Target bucket '{}' not found in bucket list", s3Props.bucket());
                        logger.info("Available buckets:");
                        response.buckets().forEach(bucket ->
                                logger.info("  - {}", bucket.name()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("❌ S3 connectivity test failed: {}", throwable.getMessage());
                    logger.error("This may indicate S3Mock is not running or not accessible");
                    logger.error("Check that S3Mock is running on: {}", s3Props.endpoint());
                    return null;
                });
    }
}