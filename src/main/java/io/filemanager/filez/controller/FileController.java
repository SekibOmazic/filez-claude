package io.filemanager.filez.controller;

import io.filemanager.filez.dto.FileStatusResponse;
import io.filemanager.filez.dto.UploadRequest;
import io.filemanager.filez.dto.UploadResponse;
import io.filemanager.filez.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    private static final long MAX_FILE_SIZE = DataSize.ofTerabytes(1).toBytes();

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * RAW STREAMING upload - ZERO buffering, works with any file size.
     * This completely bypasses Spring's multipart parsing to avoid disk buffering.
     *
     * Usage from curl:
     * curl -X POST http://localhost:8080/api/v1/files/upload \
     *   -H "Content-Type: application/octet-stream" \
     *   -H "X-Filename: myfile.pdf" \
     *   -H "X-Content-Type: application/pdf" \
     *   --data-binary @myfile.pdf
     */
    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<ResponseEntity<UploadResponse>> uploadFile(
            @RequestHeader("X-Filename") String filename,
            @RequestHeader(value = "X-Content-Type", defaultValue = "application/octet-stream") String contentType,
            ServerHttpRequest request) {

        logger.info("📤 ZERO-BUFFER upload: {} ({})", filename, contentType);

        if (filename == null || filename.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .header("X-Error", "X-Filename header is required")
                    .build());
        }

        UploadRequest uploadRequest = new UploadRequest(filename.trim(), contentType);

        // Get the raw request body - PURE STREAMING, NO BUFFERING
        Flux<DataBuffer> zeroBufferStream = request.getBody()
                .doOnSubscribe(s -> logger.info("🚀 Zero-buffer stream started: {}", filename))
                .doOnNext(buffer -> logger.debug("📦 Streaming chunk: {} bytes", buffer.readableByteCount()))
                .doOnComplete(() -> logger.info("✅ Zero-buffer stream completed: {}", filename))
                .doOnError(error -> logger.error("❌ Zero-buffer stream error: {}", error.getMessage()));

        return fileService.initiateUpload(uploadRequest, zeroBufferStream)
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(uploadResponse))
                .onErrorResume(throwable -> {
                    logger.error("Zero-buffer upload failed: {}", throwable.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .header("X-Error-Details", throwable.getMessage())
                            .build());
                });
    }

    /**
     * STREAMING MULTIPART upload that manually parses multipart without disk buffering.
     * This reads the multipart stream manually to extract filename and content.
     */
    @PostMapping(value = "/upload-multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<UploadResponse>> uploadMultipartFile(ServerHttpRequest request) {

        logger.info("📤 Manual multipart parsing upload");

        String contentTypeHeader = request.getHeaders().getFirst("Content-Type");
        if (contentTypeHeader == null || !contentTypeHeader.contains("boundary=")) {
            return Mono.just(ResponseEntity.badRequest()
                    .header("X-Error", "Missing multipart boundary")
                    .build());
        }

        // Extract boundary
        String boundary = extractBoundary(contentTypeHeader);
        logger.info("🔍 Multipart boundary: {}", boundary);

        // Parse multipart stream manually without buffering
        return parseMultipartStream(request.getBody(), boundary)
                .flatMap(uploadData -> {
                    logger.info("📝 Parsed multipart: filename={}, contentType={}, dataSize={}",
                            uploadData.filename, uploadData.contentType, uploadData.dataSize);

                    UploadRequest uploadRequest = new UploadRequest(uploadData.filename, uploadData.contentType);

                    return fileService.initiateUpload(uploadRequest, uploadData.fileStream);
                })
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(uploadResponse))
                .onErrorResume(throwable -> {
                    logger.error("Manual multipart upload failed: {}", throwable.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .header("X-Error-Details", throwable.getMessage())
                            .build());
                });
    }

    /**
     * Callback endpoint for AVScan service - pure streaming
     */
    @PostMapping("/upload-scanned")
    public Mono<ResponseEntity<String>> handleScannedUpload(
            @RequestParam("ref") String scanReferenceId,
            @RequestBody Flux<DataBuffer> cleanFileStream) {

        logger.info("📥 Scanned file callback: {}", scanReferenceId);

        Flux<DataBuffer> trackedStream = cleanFileStream
                .doOnNext(buffer -> logger.debug("📦 Clean chunk: {} bytes", buffer.readableByteCount()))
                .doOnComplete(() -> logger.info("✅ Clean stream completed: {}", scanReferenceId));

        return fileService.handleScannedFile(scanReferenceId, trackedStream)
                .then(Mono.just(ResponseEntity.ok("Scanned file processed")))
                .onErrorReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to process scanned file"));
    }

    /**
     * Get file status
     */
    @GetMapping("/{fileId}/status")
    public Mono<ResponseEntity<FileStatusResponse>> getFileStatus(@PathVariable UUID fileId) {
        return fileService.getFileStatus(fileId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    /**
     * Download file - pure streaming
     */
    @GetMapping("/{fileId}/download")
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadFile(@PathVariable UUID fileId) {
        return fileService.getFileForDownload(fileId)
                .map(fileEntity -> {
                    Flux<DataBuffer> fileStream = fileService.streamFileContent(fileEntity.s3Key());

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType(fileEntity.contentType()));
                    headers.setContentDispositionFormData("attachment", fileEntity.filename());

                    if (fileEntity.fileSize() != null) {
                        headers.setContentLength(fileEntity.fileSize());
                    }

                    return ResponseEntity.ok().headers(headers).body(fileStream);
                })
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    // --- Utility Methods ---

    private String extractBoundary(String contentType) {
        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring("boundary=".length());
            }
        }
        throw new RuntimeException("No boundary found in Content-Type");
    }

    private Mono<MultipartUploadData> parseMultipartStream(Flux<DataBuffer> dataStream, String boundary) {
        AtomicReference<String> filename = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>("application/octet-stream");
        AtomicBoolean foundFileStart = new AtomicBoolean(false);
        AtomicLong dataSize = new AtomicLong(0);

        String boundaryMarker = "--" + boundary;

        // Simple multipart parser that extracts file content without buffering
        Flux<DataBuffer> fileContentStream = dataStream
                .doOnNext(buffer -> {
                    if (!foundFileStart.get()) {
                        // Parse headers to extract filename and content-type
                        String content = extractStringFromBuffer(buffer);

                        if (content.contains("filename=")) {
                            String extractedFilename = extractFilename(content);
                            if (extractedFilename != null) {
                                filename.set(extractedFilename);
                                logger.debug("📝 Extracted filename: {}", extractedFilename);
                            }
                        }

                        if (content.contains("Content-Type:")) {
                            String extractedContentType = extractContentType(content);
                            if (extractedContentType != null) {
                                contentType.set(extractedContentType);
                                logger.debug("📝 Extracted content-type: {}", extractedContentType);
                            }
                        }

                        // Look for end of headers (double CRLF)
                        if (content.contains("\r\n\r\n")) {
                            foundFileStart.set(true);
                            logger.debug("📍 Found start of file content");
                        }
                    } else {
                        dataSize.addAndGet(buffer.readableByteCount());
                    }
                })
                .skipWhile(buffer -> !foundFileStart.get())
                .takeWhile(buffer -> {
                    // Stop when we hit the end boundary
                    String content = extractStringFromBuffer(buffer);
                    return !content.contains(boundaryMarker);
                });

        return Mono.just(new MultipartUploadData(
                filename.get() != null ? filename.get() : "unknown",
                contentType.get(),
                fileContentStream,
                dataSize.get()
        ));
    }

    private String extractStringFromBuffer(DataBuffer buffer) {
        byte[] bytes = new byte[Math.min(buffer.readableByteCount(), 1024)]; // Limit to prevent huge header parsing
        buffer.read(bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String extractFilename(String content) {
        int start = content.indexOf("filename=\"");
        if (start >= 0) {
            start += "filename=\"".length();
            int end = content.indexOf("\"", start);
            if (end >= 0) {
                return content.substring(start, end);
            }
        }
        return null;
    }

    private String extractContentType(String content) {
        int start = content.indexOf("Content-Type:");
        if (start >= 0) {
            start += "Content-Type:".length();
            int end = content.indexOf("\r\n", start);
            if (end >= 0) {
                return content.substring(start, end).trim();
            }
        }
        return null;
    }

    private static class MultipartUploadData {
        final String filename;
        final String contentType;
        final Flux<DataBuffer> fileStream;
        final long dataSize;

        MultipartUploadData(String filename, String contentType, Flux<DataBuffer> fileStream, long dataSize) {
            this.filename = filename;
            this.contentType = contentType;
            this.fileStream = fileStream;
            this.dataSize = dataSize;
        }
    }
}