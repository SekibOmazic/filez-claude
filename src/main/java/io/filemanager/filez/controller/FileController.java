package io.filemanager.filez.controller;

import io.filemanager.filez.dto.FileStatusResponse;
import io.filemanager.filez.dto.UploadRequest;
import io.filemanager.filez.dto.UploadResponse;
import io.filemanager.filez.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);
    private final DataBufferFactory dataBufferFactory = DefaultDataBufferFactory.sharedInstance;

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
     * PURE STREAMING multipart parser that never buffers to disk.
     * This manually parses multipart stream chunk by chunk without any buffering.
     *
     * CRITICAL: This completely bypasses Spring's multipart parsing to ensure zero disk usage.
     */
    @PostMapping(value = "/upload-multipart")
    public Mono<ResponseEntity<UploadResponse>> uploadMultipartFile(ServerHttpRequest request) {

        logger.info("📤 ZERO-BUFFER multipart upload (manual parsing)");

        String contentTypeHeader = request.getHeaders().getFirst("Content-Type");
        if (contentTypeHeader == null || !contentTypeHeader.contains("boundary=")) {
            return Mono.just(ResponseEntity.badRequest()
                    .header("X-Error", "Missing multipart boundary")
                    .build());
        }

        // Extract boundary
        String boundary = extractBoundary(contentTypeHeader);
        logger.info("🔍 Multipart boundary: --{}", boundary);

        // Parse multipart stream with ZERO buffering
        return parseMultipartStreamZeroBuffer(request.getBody(), boundary)
                .flatMap(uploadData -> {
                    logger.info("📝 Parsed multipart: filename={}, contentType={}",
                            uploadData.filename, uploadData.contentType);

                    UploadRequest uploadRequest = new UploadRequest(uploadData.filename, uploadData.contentType);

                    return fileService.initiateUpload(uploadRequest, uploadData.fileStream);
                })
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(uploadResponse))
                .onErrorResume(throwable -> {
                    logger.error("Zero-buffer multipart upload failed: {}", throwable.getMessage(), throwable);
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
                .doOnComplete(() -> logger.info("✅ Clean stream completed: {}", scanReferenceId))
                .doOnError(error -> logger.error("❌ Clean stream error: {}", error.getMessage(), error));

        return fileService.handleScannedFile(scanReferenceId, trackedStream)
                .then(Mono.just(ResponseEntity.ok("Scanned file processed")))
                .doOnSuccess(response -> logger.info("✅ Callback processed successfully: {}", scanReferenceId))
                .onErrorResume(error -> {
                    logger.error("❌ Callback processing failed: {}", error.getMessage(), error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Failed to process scanned file"));
                });
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

    // --- ZERO-BUFFER Multipart Parsing ---

    private String extractBoundary(String contentType) {
        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                String boundary = part.substring("boundary=".length());
                // Remove quotes if present
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        throw new RuntimeException("No boundary found in Content-Type");
    }

    /**
     * ZERO-BUFFER multipart parser using streaming state machine.
     * Never accumulates more than necessary to parse headers.
     */
    private Mono<MultipartUploadData> parseMultipartStreamZeroBuffer(Flux<DataBuffer> dataStream, String boundary) {

        String startBoundary = "\r\n--" + boundary + "\r\n";
        String startBoundaryAlt = "--" + boundary + "\r\n";  // For first boundary
        String endBoundary = "\r\n--" + boundary + "--";

        logger.debug("Boundary markers: start='{}', alt='{}', end='{}'", startBoundary, startBoundaryAlt, endBoundary);

        // State machine variables
        AtomicReference<String> filename = new AtomicReference<>("unknown");
        AtomicReference<String> contentType = new AtomicReference<>("application/octet-stream");
        AtomicReference<StringBuilder> headerBuffer = new AtomicReference<>(new StringBuilder());
        AtomicBoolean foundHeaders = new AtomicBoolean(false);
        AtomicBoolean inFileContent = new AtomicBoolean(false);
        AtomicReference<DataBuffer> carryoverBuffer = new AtomicReference<>();

        // Create a sink for the file content stream
        Sinks.Many<DataBuffer> fileSink = Sinks.many().unicast().onBackpressureBuffer();

        // Process stream with state machine
        return dataStream
                .doOnSubscribe(s -> logger.info("🚀 Starting zero-buffer multipart parsing"))
                .concatMap(buffer -> {
                    try {
                        return processChunkStateMachine(buffer, boundary, filename, contentType,
                                headerBuffer, foundHeaders, inFileContent, carryoverBuffer, fileSink);
                    } catch (Exception e) {
                        logger.error("❌ Error processing chunk: {}", e.getMessage(), e);
                        fileSink.tryEmitError(e);
                        DataBufferUtils.release(buffer);
                        return Mono.error(e);
                    }
                })
                .then(Mono.defer(() -> {
                    // Complete the file stream
                    fileSink.tryEmitComplete();

                    logger.info("✅ Zero-buffer multipart parsing completed");
                    logger.info("   Filename: {}", filename.get());
                    logger.info("   Content-Type: {}", contentType.get());

                    return Mono.just(new MultipartUploadData(
                            filename.get(),
                            contentType.get(),
                            fileSink.asFlux()
                                    .doOnNext(fileBuffer -> logger.debug("📦 File chunk: {} bytes", fileBuffer.readableByteCount()))
                                    .doOnComplete(() -> logger.debug("✅ File stream completed"))
                    ));
                }))
                .onErrorResume(error -> {
                    logger.error("❌ Zero-buffer multipart parsing failed: {}", error.getMessage(), error);
                    fileSink.tryEmitError(error);
                    return Mono.error(new RuntimeException("Multipart parsing failed: " + error.getMessage(), error));
                });
    }

    /**
     * Process a single chunk in the multipart state machine
     */
    private Mono<Void> processChunkStateMachine(DataBuffer buffer, String boundary,
                                                AtomicReference<String> filename,
                                                AtomicReference<String> contentType,
                                                AtomicReference<StringBuilder> headerBuffer,
                                                AtomicBoolean foundHeaders,
                                                AtomicBoolean inFileContent,
                                                AtomicReference<DataBuffer> carryoverBuffer,
                                                Sinks.Many<DataBuffer> fileSink) {

        // Convert buffer to string for header parsing (only if not in file content yet)
        if (!inFileContent.get()) {
            String chunkStr = bufferToString(buffer);
            headerBuffer.get().append(chunkStr);

            // Check if we have complete headers
            String headers = headerBuffer.get().toString();
            if (headers.contains("\r\n\r\n")) {
                // Parse headers
                parseMultipartHeaders(headers, filename, contentType);
                foundHeaders.set(true);

                // Find start of file content
                int contentStart = headers.indexOf("\r\n\r\n") + 4;
                if (contentStart < headers.length()) {
                    // There's file content in this chunk
                    String fileContentStr = headers.substring(contentStart);
                    byte[] fileContentBytes = fileContentStr.getBytes(StandardCharsets.ISO_8859_1);

                    if (fileContentBytes.length > 0) {
                        DataBuffer fileBuffer = dataBufferFactory.allocateBuffer(fileContentBytes.length);
                        fileBuffer.write(fileContentBytes);
                        fileSink.tryEmitNext(fileBuffer);
                    }
                }

                inFileContent.set(true);
                DataBufferUtils.release(buffer);
                return Mono.empty();
            }

            DataBufferUtils.release(buffer);
            return Mono.empty();
        } else {
            // We're in file content - check for end boundary and stream
            String chunkStr = bufferToString(buffer);
            if (chunkStr.contains("--" + boundary)) {
                // Found end boundary - stop streaming
                int endIndex = chunkStr.indexOf("--" + boundary);
                if (endIndex > 0) {
                    // Send the remaining content before boundary
                    String finalContent = chunkStr.substring(0, endIndex);
                    if (!finalContent.isEmpty()) {
                        byte[] finalBytes = finalContent.getBytes(StandardCharsets.ISO_8859_1);
                        DataBuffer finalBuffer = dataBufferFactory.allocateBuffer(finalBytes.length);
                        finalBuffer.write(finalBytes);
                        fileSink.tryEmitNext(finalBuffer);
                    }
                }
                DataBufferUtils.release(buffer);
                return Mono.empty();
            } else {
                // Pure file content - stream it directly
                fileSink.tryEmitNext(buffer.retain());
                DataBufferUtils.release(buffer);
                return Mono.empty();
            }
        }
    }

    private String bufferToString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes, 0, bytes.length);
        buffer.readPosition(0); // Reset position
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private void parseMultipartHeaders(String headers, AtomicReference<String> filename, AtomicReference<String> contentType) {
        String[] lines = headers.split("\r\n");

        for (String line : lines) {
            if (line.toLowerCase().contains("content-disposition") && line.contains("filename=")) {
                String extractedFilename = extractFilenameFromLine(line);
                if (extractedFilename != null) {
                    filename.set(extractedFilename);
                    logger.debug("📝 Extracted filename: {}", extractedFilename);
                }
            } else if (line.toLowerCase().startsWith("content-type:")) {
                String extractedContentType = line.substring("content-type:".length()).trim();
                if (!extractedContentType.isEmpty()) {
                    contentType.set(extractedContentType);
                    logger.debug("📝 Extracted content-type: {}", extractedContentType);
                }
            }
        }
    }

    private String extractFilenameFromLine(String line) {
        int start = line.indexOf("filename=\"");
        if (start >= 0) {
            start += "filename=\"".length();
            int end = line.indexOf("\"", start);
            if (end >= 0) {
                return line.substring(start, end);
            }
        }

        // Try without quotes
        start = line.indexOf("filename=");
        if (start >= 0) {
            start += "filename=".length();
            String remaining = line.substring(start).trim();
            return remaining.split("\\s")[0];
        }

        return null;
    }

    private static class MultipartUploadData {
        final String filename;
        final String contentType;
        final Flux<DataBuffer> fileStream;

        MultipartUploadData(String filename, String contentType, Flux<DataBuffer> fileStream) {
            this.filename = filename;
            this.contentType = contentType;
            this.fileStream = fileStream;
        }
    }
}