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
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(-1))
                .build();
    }

    /**
     * Streams file content to AVScan service for virus scanning.
     * AVScan will scan the content and stream clean content to our callback URL.
     */
    public Mono<String> scanFile(Flux<DataBuffer> fileStream,
                                 String scanReferenceId,
                                 String filename,
                                 String contentType) {

        String callbackUrl = buildCallbackUrl(scanReferenceId);

        logger.info("Sending file {} (ref: {}) to AVScan service. Callback URL: {}",
                filename, scanReferenceId, callbackUrl);

        return webClient
                .post()
                .uri(appProperties.avScan().endpoint())
                .header("targetUrl", callbackUrl)
                .header("scan-reference-id", scanReferenceId)
                .header("original-filename", filename)
                .header("original-content-type", contentType)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromDataBuffers(fileStream))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(appProperties.avScan().timeout())
                .doOnSuccess(response -> logger.info("AVScan accepted file for scanning: {}", scanReferenceId))
                .doOnError(error -> logger.error("Failed to send file to AVScan: {}", error.getMessage()))
                .onErrorMap(throwable -> new RuntimeException("AVScan service error", throwable));
        // Note: DataBuffers are automatically released by WebClient when consuming the flux
    }

    private String buildCallbackUrl(String scanReferenceId) {
        return appProperties.callback().baseUrl() + "/api/v1/files/upload-scanned?ref=" + scanReferenceId;
    }
}