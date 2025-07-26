package io.filemanager.filez.service;

import io.filemanager.filez.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AVScanService {

    private static final Logger logger = LoggerFactory.getLogger(AVScanService.class);

    private final WebClient webClient;
    private final AppProperties appProperties;

    public AVScanService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.webClient = WebClient.builder()
                .codecs(configurer -> {
                    // CRITICAL: Unlimited to avoid any buffering limits
                    configurer.defaultCodecs().maxInMemorySize(-1);
                    // Enable streaming mode
                    configurer.defaultCodecs().enableLoggingRequestDetails(true);
                })
                .build();
    }

    /**
     * PURE STREAMING to AVScan - never buffers the entire file.
     * The fileStream is passed through directly without any intermediate buffering.
     */
    public Mono<String> scanFile(Flux<DataBuffer> fileStream,
                                 String scanReferenceId,
                                 String filename,
                                 String contentType) {

        String callbackUrl = buildCallbackUrl(scanReferenceId);

        logger.info("🔍 Streaming file to AVScan: {} (ref: {})", filename, scanReferenceId);
        logger.info("📞 Callback URL: {}", callbackUrl);

        // Create a PURE streaming flux that tracks bytes but never accumulates
        Flux<DataBuffer> trackingStream = fileStream
                .doOnSubscribe(s -> logger.info("🚀 Starting AVScan stream for: {}", filename))
                .doOnNext(buffer -> {
                    // Only log individual chunks - never accumulate
                    logger.debug("📤 Sending to AVScan: {} bytes", buffer.readableByteCount());
                })
                .doOnComplete(() -> logger.info("✅ Stream to AVScan completed for: {}", filename))
                .doOnError(error -> logger.error("❌ Stream to AVScan failed for {}: {}", filename, error.getMessage()))
                .doFinally(signal -> logger.debug("🏁 AVScan stream finished with signal: {}", signal));

        return webClient
                .post()
                .uri(appProperties.avScan().endpoint())
                .header("targetUrl", callbackUrl)
                .header("scan-reference-id", scanReferenceId)
                .header("original-filename", filename)
                .header("original-content-type", contentType)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // Use fromDataBuffers for pure streaming - no intermediate buffering
                .body(BodyInserters.fromDataBuffers(trackingStream))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(appProperties.avScan().timeout())
                .doOnSuccess(response -> logger.info("✅ AVScan accepted file: {} -> {}", scanReferenceId, response))
                .doOnError(error -> logger.error("❌ AVScan failed for {}: {}", scanReferenceId, error.getMessage()))
                .onErrorMap(throwable -> new RuntimeException("AVScan service error: " + throwable.getMessage(), throwable));
    }

    private String buildCallbackUrl(String scanReferenceId) {
        return appProperties.callback().baseUrl() + "/api/v1/files/upload-scanned?ref=" + scanReferenceId;
    }
}