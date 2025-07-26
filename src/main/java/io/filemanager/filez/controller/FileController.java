package io.filemanager.filez.controller;

import io.filemanager.filez.dto.FileStatusResponse;
import io.filemanager.filez.dto.UploadRequest;
import io.filemanager.filez.dto.UploadResponse;
import io.filemanager.filez.service.FileService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
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
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<UploadResponse>> uploadFile(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestPart("metadata") @Valid UploadRequest uploadRequest) {

        logger.info("Received file upload request: {}", uploadRequest.filename());

        return filePartMono
                .flatMap(filePart -> {
                    // Validate file part
                    if (filePart.filename() == null || filePart.filename().isEmpty()) {
                        return Mono.error(new RuntimeException("Filename is required"));
                    }

                    // Create upload request with actual file data
                    UploadRequest actualRequest = new UploadRequest(
                            filePart.filename(),
                            uploadRequest.contentType() != null ? uploadRequest.contentType() :
                                    determineContentType(filePart)
                    );

                    // Get Content-Length if available (many clients don't send this for large files)
                    Long declaredSize = getContentLength(filePart);
                    logger.info("File upload - declared size: {} bytes ({})",
                            declaredSize, declaredSize != null ? formatBytes(declaredSize) : "unknown");

                    // Enhanced streaming with size tracking and validation
                    AtomicLong totalBytes = new AtomicLong(0);
                    AtomicLong lastLoggedSize = new AtomicLong(0);
                    long startTime = System.currentTimeMillis();

                    Flux<DataBuffer> enhancedFileStream = filePart.content()
                            .doOnSubscribe(subscription -> logger.info("Starting file stream for: {}", actualRequest.filename()))
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
                                                actualRequest.filename(), formatBytes(currentTotal), String.format("%.2f", mbPerSecond));
                                    }
                                }
                            })
                            .doOnComplete(() -> {
                                long finalSize = totalBytes.get();
                                long elapsed = System.currentTimeMillis() - startTime;
                                double mbPerSecond = (finalSize / 1024.0 / 1024.0) / (elapsed / 1000.0);
                                logger.info("Upload stream completed - {}: {} in {}ms ({} MB/s)",
                                        actualRequest.filename(), formatBytes(finalSize), elapsed, String.format("%.2f", mbPerSecond));
                            })
                            .doOnError(error -> logger.error("Upload stream error for {}: {}",
                                    actualRequest.filename(), error.getMessage()))
                            // Add timeout for really large files (24 hours)
                            .timeout(Duration.ofHours(24), Mono.error(new RuntimeException("Upload timeout after 24 hours")));

                    return fileService.initiateUpload(actualRequest, enhancedFileStream);
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