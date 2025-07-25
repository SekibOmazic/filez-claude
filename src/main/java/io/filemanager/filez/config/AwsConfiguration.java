package io.filemanager.filez.config;

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

    @Bean
    public S3AsyncClient s3AsyncClient(AppProperties appProperties) {
        var s3Props = appProperties.s3();

        var credentials = AwsBasicCredentials.create(
                s3Props.accessKey(),
                s3Props.secretKey()
        );

        var httpClient = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(100)
                .maxPendingConnectionAcquires(10000)
                .connectionTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();

        var s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(s3Props.pathStyleAccess())
                .checksumValidationEnabled(false)  // Disable for S3Mock compatibility
                .build();

        return S3AsyncClient.builder()
                .region(Region.of(s3Props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(URI.create(s3Props.endpoint()))
                .serviceConfiguration(s3Config)
                .httpClient(httpClient)
                .forcePathStyle(s3Props.pathStyleAccess())  // Additional config for newer SDK
                .build();
    }
}