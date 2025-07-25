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
        logger.info("Starting S3 upload for key: {}", s3Key);

        AtomicLong totalBytes = new AtomicLong(0);

        // Convert DataBuffer flux to ByteBuffer flux for S3
        Flux<ByteBuffer> byteBufferFlux = fileStream
                .doOnNext(dataBuffer -> totalBytes.addAndGet(dataBuffer.readableByteCount()))
                .map(dataBuffer -> {
                    ByteBuffer byteBuffer = dataBuffer.toByteBuffer();
                    DataBufferUtils.release(dataBuffer); // Release to prevent memory leaks
                    return byteBuffer;
                })
                .doOnComplete(() -> logger.debug("File stream completed, total bytes: {}", totalBytes.get()))
                .doOnError(error -> logger.error("Error in file stream: {}", error.getMessage()));

        // Create AsyncRequestBody from ByteBuffer flux
        AsyncRequestBody requestBody = AsyncRequestBody.fromPublisher(byteBufferFlux);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(appProperties.s3().bucket())
                .key(s3Key)
                .contentType(contentType)
                .build();

        return Mono.fromFuture(s3AsyncClient.putObject(putRequest, requestBody))
                .map(response -> {
                    logger.info("S3 upload completed for key: {}, size: {} bytes", s3Key, totalBytes.get());
                    return totalBytes.get();
                })
                .onErrorMap(throwable -> {
                    logger.error("S3 upload failed for key: {}", s3Key, throwable);
                    return new RuntimeException("S3 upload failed", throwable);
                });
    }

    /**
     * Downloads file from S3 as a streaming response.
     */
    public Flux<DataBuffer> downloadFile(String s3Key) {
        logger.info("Starting S3 download for key: {}", s3Key);

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(appProperties.s3().bucket())
                .key(s3Key)
                .build();

        return Mono.fromFuture(
                        s3AsyncClient.getObject(getRequest, AsyncResponseTransformer.toPublisher())
                )
                .flatMapMany(response -> {
                    logger.info("S3 download started for key: {}, content-length: {}",
                            s3Key, response.response().contentLength());

                    return Flux.from(response)
                            .map(byteBuffer -> (DataBuffer) dataBufferFactory.wrap(byteBuffer));
                })
                .doOnComplete(() -> logger.info("S3 download completed for key: {}", s3Key))
                .onErrorMap(throwable -> {
                    logger.error("S3 download failed for key: {}", s3Key, throwable);
                    return new RuntimeException("S3 download failed", throwable);
                });
    }

    /**
     * Generates S3 key for a file.
     */
    public String generateS3Key(String uploadSessionId, String filename) {
        return String.format("files/%s/%s", uploadSessionId, filename);
    }
}