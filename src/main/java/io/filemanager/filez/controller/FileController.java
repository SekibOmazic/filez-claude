package io.filemanager.filez.controller;

import io.filemanager.filez.dto.FileStatusResponse;
import io.filemanager.filez.dto.UploadRequest;
import io.filemanager.filez.dto.UploadResponse;
import io.filemanager.filez.service.FileService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    // Support really large files - configurable via properties
    private static final long MAX_FILE_SIZE = DataSize.ofTerabytes(1).toBytes(); // 1TB limit!
    private static final int CHUNK_SIZE = 8192; // 8KB chunks for optimal streaming

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Enhanced upload endpoint that handles:
     * 1. Really large files (TB scale)
     * 2. Unknown file sizes (no Content-Length header)
     * 3. Progressive size validation during streaming
     * 4. Streaming rate monitoring
     */
    @PostMapping(value = "/upload1", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<UploadResponse>> uploadFile1(
            @RequestPart("file") Mono<FilePart> filePartMono) {

        logger.info("Received file upload request");

        return filePartMono
                .flatMap(filePart -> {
                    // Validate file part
                    if (filePart.filename() == null || filePart.filename().isEmpty()) {
                        return Mono.error(new RuntimeException("Filename is required"));
                    }

                    // Extract metadata from FilePart (no need for separate UploadRequest)
                    String filename = filePart.filename();
                    String contentType = determineContentType(filePart);

                    // Create upload request from FilePart data
                    UploadRequest uploadRequest = new UploadRequest(filename, contentType);

                    logger.info("Processing file upload: {} ({})", filename, contentType);

                    // Get Content-Length if available
                    Long declaredSize = getContentLength(filePart);
                    logger.info("File upload - declared size: {} bytes ({})",
                            declaredSize, declaredSize != null ? formatBytes(declaredSize) : "unknown");

                    // Enhanced streaming with size tracking and validation
                    AtomicLong totalBytes = new AtomicLong(0);
                    AtomicLong lastLoggedSize = new AtomicLong(0);
                    long startTime = System.currentTimeMillis();

                    Flux<DataBuffer> enhancedFileStream = filePart.content()
                            .doOnSubscribe(subscription -> logger.info("Starting file stream for: {}", filename))
                            .doOnNext(dataBuffer -> {
                                long currentTotal = totalBytes.addAndGet(dataBuffer.readableByteCount());

                                // Progressive size validation
                                if (currentTotal > MAX_FILE_SIZE) {
                                    throw new RuntimeException(String.format(
                                            "File size exceeds maximum allowed size of %s (current: %s)",
                                            formatBytes(MAX_FILE_SIZE), formatBytes(currentTotal)));
                                }

                                // Log progress every 100MB for large files
                                long lastLogged = lastLoggedSize.get();
                                if (currentTotal - lastLogged > DataSize.ofMegabytes(100).toBytes()) {
                                    if (lastLoggedSize.compareAndSet(lastLogged, currentTotal)) {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        double mbPerSecond = (currentTotal / 1024.0 / 1024.0) / (elapsed / 1000.0);
                                        logger.info("Upload progress - {}: {} ({} MB/s)",
                                                filename, formatBytes(currentTotal), String.format("%.2f", mbPerSecond));
                                    }
                                }
                            })
                            .doOnComplete(() -> {
                                long finalSize = totalBytes.get();
                                long elapsed = System.currentTimeMillis() - startTime;
                                double mbPerSecond = (finalSize / 1024.0 / 1024.0) / (elapsed / 1000.0);
                                logger.info("Upload stream completed - {}: {} in {}ms ({} MB/s)",
                                        filename, formatBytes(finalSize), elapsed, String.format("%.2f", mbPerSecond));
                            })
                            .doOnError(error -> logger.error("Upload stream error for {}: {}",
                                    filename, error.getMessage()))
                            // Add timeout for really large files (24 hours)
                            .timeout(Duration.ofHours(24), Mono.error(new RuntimeException("Upload timeout after 24 hours")));

                    return fileService.initiateUpload(uploadRequest, enhancedFileStream);
                })
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(uploadResponse))
                .onErrorResume(throwable -> {
                    logger.error("Upload failed: {}", throwable.getMessage());

                    if (throwable.getMessage().contains("File size exceeds")) {
                        return Mono.just(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                                .header("X-Error-Details", throwable.getMessage())
                                .build());
                    }

                    if (throwable.getMessage().contains("Upload timeout")) {
                        return Mono.just(ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                                .header("X-Error-Details", "Upload timeout - file may be too large")
                                .build());
                    }

                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .header("X-Error-Details", throwable.getMessage())
                            .build());
                });
    }


    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<UploadResponse>> uploadFile(
            @RequestHeader("Content-Type") String contentTypeHeader,
            @RequestBody Flux<DataBuffer> bodyStream) {

        logger.info("Received file upload request");

        // Extract boundary
        if (!contentTypeHeader.contains("boundary=")) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        // For now, assume the file data starts after headers
        // Skip multipart headers and extract just the file content
        AtomicBoolean foundFileStart = new AtomicBoolean(false);

        Flux<DataBuffer> fileContentStream = bodyStream
                .skipWhile(buffer -> {
                    if (foundFileStart.get()) return false;

                    String content = buffer.toString(StandardCharsets.UTF_8);
                    if (content.contains("Content-Type:") || content.contains("filename=")) {
                        // Found file headers, next buffer should be content
                        foundFileStart.set(true);
                        DataBufferUtils.release(buffer);
                        return true;
                    }
                    DataBufferUtils.release(buffer);
                    return true;
                })
                .takeWhile(buffer -> {
                    // Stop at boundary end
                    String content = buffer.toString(StandardCharsets.UTF_8);
                    return !content.contains("------");
                });

        // Create a dummy upload request (you'd parse this from headers)
        UploadRequest uploadRequest = new UploadRequest("streaming-upload.bin", "application/octet-stream");

        return fileService.initiateUpload(uploadRequest, fileContentStream)
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(uploadResponse))
                .onErrorResume(throwable -> {
                    logger.error("Upload failed: {}", throwable.getMessage());

                    if (throwable.getMessage().contains("File size exceeds")) {
                        return Mono.just(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build());
                    }

                    if (throwable.getMessage().contains("Upload timeout")) {
                        return Mono.just(ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).build());
                    }

                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
                });
    }

    @PostMapping(value = "/upload-debug", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> uploadFileDebug(ServerHttpRequest request) {

        logger.info("=== UPLOAD DEBUG ===");
        logger.info("Method: {}", request.getMethod());
        logger.info("Headers: {}", request.getHeaders());
        logger.info("Content-Type: {}", request.getHeaders().getContentType());

        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null || !contentType.includes(MediaType.MULTIPART_FORM_DATA)) {
            Map<String, Object> errorResponse = Map.of(
                    "error", "Expected multipart/form-data",
                    "received", String.valueOf(contentType)
            );
            return Mono.just(ResponseEntity.badRequest().body(errorResponse));
        }

        AtomicLong totalBytes = new AtomicLong(0);

        return request.getBody()
                .doOnNext(buffer -> {
                    long bytes = buffer.readableByteCount();
                    totalBytes.addAndGet(bytes);
                    logger.info("Received {} bytes (total: {})", bytes, totalBytes.get());
                    DataBufferUtils.release(buffer); // Always release!
                })
                .doOnComplete(() -> logger.info("Stream completed: {} total bytes", totalBytes.get()))
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> successResponse = new HashMap<>();
                    successResponse.put("status", "success");
                    successResponse.put("totalBytes", totalBytes.get());
                    return ResponseEntity.ok(successResponse);
                }))
                .onErrorResume(error -> {
                    logger.error("Upload error: {}", error.getMessage(), error);
                    Map<String, Object> errorResponse = Map.of("error", "Processing failed");
                    return Mono.just(ResponseEntity.badRequest().body(errorResponse));
                });
    }


    /**
     * Enhanced callback endpoint that can handle unknown file sizes
     */
    @PostMapping("/upload-scanned")
    public Mono<ResponseEntity<String>> handleScannedUpload(
            @RequestParam("ref") String scanReferenceId,
            @RequestBody Flux<DataBuffer> cleanFileStream) {

        logger.info("Received scanned file callback for reference: {}", scanReferenceId);

        // Add size tracking for the clean file stream too
        AtomicLong cleanFileBytes = new AtomicLong(0);

        Flux<DataBuffer> trackedCleanStream = cleanFileStream
                .doOnNext(dataBuffer -> cleanFileBytes.addAndGet(dataBuffer.readableByteCount()))
                .doOnComplete(() -> logger.info("Clean file stream completed - reference {}: {} bytes",
                        scanReferenceId, formatBytes(cleanFileBytes.get())))
                .timeout(Duration.ofHours(24)); // Same timeout as upload

        return fileService.handleScannedFile(scanReferenceId, trackedCleanStream)
                .then(Mono.just(ResponseEntity.ok("Upload completed - " + formatBytes(cleanFileBytes.get()))))
                .onErrorReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Upload failed"));
    }

    /**
     * Get file status endpoint with enhanced progress info
     */
    @GetMapping("/{fileId}/status")
    public Mono<ResponseEntity<FileStatusResponse>> getFileStatus(@PathVariable UUID fileId) {
        logger.info("Getting status for file: {}", fileId);

        return fileService.getFileStatus(fileId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    /**
     * Enhanced download endpoint with resume support preparation
     */
    @GetMapping("/{fileId}/download")
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadFile(
            @PathVariable UUID fileId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        logger.info("Download request for file: {} (Range: {})", fileId, rangeHeader);

        return fileService.getFileForDownload(fileId)
                .map(fileEntity -> {
                    Flux<DataBuffer> fileStream = fileService.streamFileContent(fileEntity.s3Key());

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType(fileEntity.contentType()));
                    headers.setContentDispositionFormData("attachment", fileEntity.filename());

                    if (fileEntity.fileSize() != null) {
                        headers.setContentLength(fileEntity.fileSize());
                        // Add headers for large file handling
                        headers.set("Accept-Ranges", "bytes");
                        headers.set("X-File-Size", formatBytes(fileEntity.fileSize()));
                    }

                    // For really large files, add streaming hints
                    if (fileEntity.fileSize() != null && fileEntity.fileSize() > DataSize.ofGigabytes(1).toBytes()) {
                        headers.set("X-Large-File", "true");
                        headers.set("X-Recommended-Chunk-Size", String.valueOf(CHUNK_SIZE));
                    }

                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(fileStream);
                })
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    /**
     * Utility method to extract Content-Length from FilePart
     * Returns null if not available (common for large files)
     */
    private Long getContentLength(FilePart filePart) {
        try {
            HttpHeaders headers = filePart.headers();
            return headers.getContentLength() > 0 ? headers.getContentLength() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Human-readable byte formatting
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        return String.format("%.2f TB", bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0));
    }

    private String determineContentType(FilePart filePart) {
        HttpHeaders headers = filePart.headers();
        MediaType contentType = headers.getContentType();
        return contentType != null ? contentType.toString() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}