package io.filemanager.filez.controller;

import io.filemanager.filez.config.AppProperties;
import io.filemanager.filez.service.S3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthCheckController {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckController.class);

    private final S3AsyncClient s3AsyncClient;
    private final S3Service s3Service;
    private final AppProperties appProperties;
    private final DataBufferFactory dataBufferFactory = DefaultDataBufferFactory.sharedInstance;

    public HealthCheckController(S3AsyncClient s3AsyncClient, S3Service s3Service, AppProperties appProperties) {
        this.s3AsyncClient = s3AsyncClient;
        this.s3Service = s3Service;
        this.appProperties = appProperties;
    }

    /**
     * Basic health check
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "status", "UP",
                "application", "Filez",
                "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * Detailed health check including S3 connectivity
     */
    @GetMapping("/detailed")
    public Mono<ResponseEntity<Map<String, Object>>> detailedHealth() {
        logger.info("🔍 Running detailed health check...");

        return s3Service.testS3Connection()
                .map(s3Status -> ResponseEntity.ok(Map.of(
                        "status", "UP",
                        "application", "Filez",
                        "timestamp", System.currentTimeMillis(),
                        "s3", Map.of(
                                "status", s3Status.contains("OK") ? "UP" : "DOWN",
                                "endpoint", appProperties.s3().endpoint(),
                                "bucket", appProperties.s3().bucket(),
                                "message", s3Status
                        ),
                        "avScan", Map.of(
                                "endpoint", appProperties.avScan().endpoint(),
                                "timeout", appProperties.avScan().timeout().toString()
                        ),
                        "callback", Map.of(
                                "baseUrl", appProperties.callback().baseUrl()
                        )
                )))
                .onErrorReturn(ResponseEntity.status(500).body(Map.of(
                        "status", "DOWN",
                        "application", "Filez",
                        "timestamp", System.currentTimeMillis(),
                        "error", "Health check failed"
                )));
    }

    /**
     * S3-specific health check
     */
    @GetMapping("/s3")
    public Mono<ResponseEntity<Map<String, Object>>> s3Health() {
        logger.info("🔍 Testing S3 connectivity...");

        return Mono.fromFuture(() -> s3AsyncClient.listBuckets())
                .map(response -> {
                    boolean bucketExists = response.buckets().stream()
                            .anyMatch(bucket -> bucket.name().equals(appProperties.s3().bucket()));

                    return ResponseEntity.ok(Map.of(
                            "status", "UP",
                            "endpoint", appProperties.s3().endpoint(),
                            "bucket", appProperties.s3().bucket(),
                            "bucketExists", bucketExists,
                            "totalBuckets", response.buckets().size(),
                            "buckets", response.buckets().stream()
                                    .map(bucket -> bucket.name())
                                    .toList()
                    ));
                })
                .doOnSuccess(response -> logger.info("✅ S3 health check passed"))
                .onErrorResume(error -> {
                    logger.error("❌ S3 health check failed: {}", error.getMessage(), error);
                    return Mono.just(ResponseEntity.status(500).body(Map.of(
                            "status", "DOWN",
                            "endpoint", appProperties.s3().endpoint(),
                            "bucket", appProperties.s3().bucket(),
                            "error", error.getMessage(),
                            "errorType", error.getClass().getSimpleName()
                    )));
                });
    }

    /**
     * Configuration dump for debugging
     */
    @GetMapping("/config")
    public Mono<ResponseEntity<Map<String, Object>>> configDump() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "s3", Map.of(
                        "endpoint", appProperties.s3().endpoint(),
                        "region", appProperties.s3().region(),
                        "bucket", appProperties.s3().bucket(),
                        "accessKey", appProperties.s3().accessKey(),
                        "pathStyleAccess", appProperties.s3().pathStyleAccess()
                ),
                "avScan", Map.of(
                        "endpoint", appProperties.avScan().endpoint(),
                        "timeout", appProperties.avScan().timeout().toString()
                ),
                "callback", Map.of(
                        "baseUrl", appProperties.callback().baseUrl()
                ),
                "upload", Map.of(
                        "maxFileSize", appProperties.upload().maxFileSize(),
                        "allowedTypes", appProperties.upload().allowedTypes(),
                        "tempCleanupInterval", appProperties.upload().tempCleanupInterval().toString()
                )
        )));
    }

    /**
     * Simple S3 upload test with a small file
     */
    @PostMapping("/test-s3-upload")
    public Mono<ResponseEntity<Map<String, Object>>> testS3Upload() {
        logger.info("🧪 Testing S3 upload with simple content...");

        String testContent = "Hello S3Mock - Test Upload!";
        String testKey = "test/" + System.currentTimeMillis() + ".txt";

        // Create a simple DataBuffer flux
        Flux<DataBuffer> testStream = Mono.fromCallable(() -> {
            DataBuffer buffer = dataBufferFactory.allocateBuffer(testContent.length());
            buffer.write(testContent.getBytes());
            return buffer;
        }).flux();

        return s3Service.uploadFile(testStream, testKey, "text/plain")
                .map(fileSize -> {
                    Map<String, Object> successResponse = Map.of(
                            "status", "SUCCESS",
                            "message", "S3 upload test successful",
                            "key", testKey,
                            "size", fileSize,
                            "content", testContent
                    );
                    return ResponseEntity.ok(successResponse);
                })
                .onErrorResume(error -> {
                    logger.error("❌ S3 upload test failed: {}", error.getMessage(), error);
                    Map<String, Object> errorResponse = Map.of(
                            "status", "FAILED",
                            "message", "S3 upload test failed",
                            "error", error.getMessage(),
                            "errorType", error.getClass().getSimpleName()
                    );
                    return Mono.just(ResponseEntity.status(500).body(errorResponse));
                });
    }
}