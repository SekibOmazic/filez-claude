package io.filemanager.filez.service;

import io.filemanager.filez.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class S3Service {

    private static final Logger logger = LoggerFactory.getLogger(S3Service.class);

    private final S3AsyncClient s3AsyncClient;
    private final AppProperties appProperties;
    private final DataBufferFactory dataBufferFactory;

    public S3Service(S3AsyncClient s3AsyncClient, AppProperties appProperties) {
        this.s3AsyncClient = s3AsyncClient;
        this.appProperties = appProperties;
        this.dataBufferFactory = DefaultDataBufferFactory.sharedInstance;
    }

    /**
     * Streams file content directly to S3 without buffering in memory.
     * Returns the uploaded file size.
     */
    public Mono<Long> uploadFile(Flux<DataBuffer> fileStream, String s3Key, String contentType) {
        logger.info("=== S3 UPLOAD START ===");
        logger.info("S3 Key: {}", s3Key);
        logger.info("Content-Type: {}", contentType);
        logger.info("Bucket: {}", appProperties.s3().bucket());
        logger.info("Endpoint: {}", appProperties.s3().endpoint());

        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong chunkCount = new AtomicLong(0);

        // Convert DataBuffer flux to ByteBuffer flux for S3
        Flux<ByteBuffer> byteBufferFlux = fileStream
                .doOnSubscribe(s -> logger.info("🚀 S3 upload stream started for: {}", s3Key))
                .doOnNext(dataBuffer -> {
                    long bytes = dataBuffer.readableByteCount();
                    totalBytes.addAndGet(bytes);
                    long chunkNum = chunkCount.incrementAndGet();
                    logger.debug("📦 S3 chunk {}: {} bytes (total: {} bytes)", chunkNum, bytes, totalBytes.get());
                })
                .map(dataBuffer -> {
                    try {
                        ByteBuffer byteBuffer = dataBuffer.toByteBuffer();
                        DataBufferUtils.release(dataBuffer); // Release to prevent memory leaks
                        return byteBuffer;
                    } catch (Exception e) {
                        logger.error("❌ Error converting DataBuffer to ByteBuffer: {}", e.getMessage());
                        DataBufferUtils.release(dataBuffer); // Ensure cleanup even on error
                        throw new RuntimeException("Buffer conversion failed", e);
                    }
                })
                .doOnComplete(() -> logger.info("✅ ByteBuffer stream completed: {} bytes in {} chunks",
                        totalBytes.get(), chunkCount.get()))
                .doOnError(error -> logger.error("❌ Error in ByteBuffer stream: {}", error.getMessage(), error));

        // Create AsyncRequestBody from ByteBuffer flux
        AsyncRequestBody requestBody;
        try {
            requestBody = AsyncRequestBody.fromPublisher(byteBufferFlux);
            logger.info("📤 AsyncRequestBody created successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to create AsyncRequestBody: {}", e.getMessage(), e);
            return Mono.error(new RuntimeException("Failed to create request body", e));
        }

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(appProperties.s3().bucket())
                .key(s3Key)
                .contentType(contentType)
                .build();

        logger.info("📋 S3 PutObject request details:");
        logger.info("   Bucket: {}", putRequest.bucket());
        logger.info("   Key: {}", putRequest.key());
        logger.info("   Content-Type: {}", putRequest.contentType());

        return Mono.fromFuture(s3AsyncClient.putObject(putRequest, requestBody))
                .doOnSubscribe(s -> logger.info("🚀 S3 putObject operation started"))
                .map(response -> {
                    logger.info("=== S3 UPLOAD SUCCESS ===");
                    logger.info("✅ S3 upload completed successfully");
                    logger.info("   Key: {}", s3Key);
                    logger.info("   Size: {} bytes", totalBytes.get());
                    logger.info("   ETag: {}", response.eTag());
                    logger.info("   Version: {}", response.versionId());
                    return totalBytes.get();
                })
                .doOnError(error -> {
                    logger.error("=== S3 UPLOAD FAILED ===");
                    logger.error("❌ S3 upload failed for key: {}", s3Key);
                    logger.error("❌ Error type: {}", error.getClass().getSimpleName());
                    logger.error("❌ Error message: {}", error.getMessage());

                    if (error instanceof S3Exception s3Error) {
                        logger.error("❌ S3 Error Details:");
                        logger.error("   Status Code: {}", s3Error.statusCode());
                        logger.error("   Error Code: {}", s3Error.awsErrorDetails().errorCode());
                        logger.error("   Error Message: {}", s3Error.awsErrorDetails().errorMessage());
                        logger.error("   Service Name: {}", s3Error.awsErrorDetails().serviceName());
                        logger.error("   Request ID: {}", s3Error.requestId());
                    }

                    logger.error("❌ Full error: ", error);
                })
                .onErrorMap(throwable -> {
                    String errorMsg = String.format("S3 upload failed for key '%s': %s", s3Key, throwable.getMessage());
                    logger.error("❌ Mapping error: {}", errorMsg);
                    return new RuntimeException(errorMsg, throwable);
                });
    }

    /**
     * Downloads file from S3 as a streaming response.
     */
    public Flux<DataBuffer> downloadFile(String s3Key) {
        logger.info("=== S3 DOWNLOAD START ===");
        logger.info("S3 Key: {}", s3Key);
        logger.info("Bucket: {}", appProperties.s3().bucket());

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(appProperties.s3().bucket())
                .key(s3Key)
                .build();

        return Mono.fromFuture(
                        s3AsyncClient.getObject(getRequest, AsyncResponseTransformer.toPublisher())
                )
                .doOnSubscribe(s -> logger.info("🚀 S3 download started for: {}", s3Key))
                .flatMapMany(response -> {
                    logger.info("✅ S3 download response received");
                    logger.info("   Content-Length: {}", response.response().contentLength());
                    logger.info("   Content-Type: {}", response.response().contentType());
                    logger.info("   ETag: {}", response.response().eTag());

                    return Flux.from(response)
                            .map(byteBuffer -> (DataBuffer) dataBufferFactory.wrap(byteBuffer))
                            .doOnNext(buffer -> logger.debug("📦 Downloaded chunk: {} bytes", buffer.readableByteCount()));
                })
                .doOnComplete(() -> logger.info("✅ S3 download completed for: {}", s3Key))
                .doOnError(error -> {
                    logger.error("❌ S3 download failed for key: {}", s3Key);
                    logger.error("❌ Error: ", error);
                })
                .onErrorMap(throwable -> {
                    String errorMsg = String.format("S3 download failed for key '%s': %s", s3Key, throwable.getMessage());
                    return new RuntimeException(errorMsg, throwable);
                });
    }

    /**
     * Generates S3 key for a file.
     */
    public String generateS3Key(String uploadSessionId, String filename) {
        String s3Key = String.format("files/%s/%s", uploadSessionId, filename);
        logger.debug("Generated S3 key: {} for session: {} and filename: {}", s3Key, uploadSessionId, filename);
        return s3Key;
    }

    /**
     * Test S3 connectivity and configuration
     */
    public Mono<String> testS3Connection() {
        logger.info("=== S3 CONNECTION TEST ===");
        logger.info("Testing S3 connectivity...");
        logger.info("Endpoint: {}", appProperties.s3().endpoint());
        logger.info("Bucket: {}", appProperties.s3().bucket());

        return Mono.fromFuture(s3AsyncClient.listObjects(builder ->
                        builder.bucket(appProperties.s3().bucket()).maxKeys(1)))
                .map(response -> {
                    logger.info("✅ S3 connection test successful");
                    logger.info("   Objects found: {}", response.contents().size());
                    return "S3 connection OK";
                })
                .doOnError(error -> {
                    logger.error("❌ S3 connection test failed");
                    logger.error("❌ Error: ", error);
                })
                .onErrorReturn("S3 connection failed: " + appProperties.s3().endpoint());
    }
}