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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    
    private static final Logger logger = LoggerFactory.getLogger(FileController.class);
    
    private final FileService fileService;
    
    public FileController(FileService fileService) {
        this.fileService = fileService;
    }
    
    /**
     * Upload file endpoint - receives multipart upload and streams to AVScan service.
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
                    
                    // Stream file content to service
                    Flux<DataBuffer> fileStream = filePart.content();
                    
                    return fileService.initiateUpload(actualRequest, fileStream);
                })
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.ACCEPTED).body(uploadResponse))
                .onErrorReturn(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
    }
    
    /**
     * Callback endpoint for AVScan service - receives clean file stream.
     */
    @PostMapping("/upload-scanned")
    public Mono<ResponseEntity<String>> handleScannedUpload(
            @RequestParam("ref") String scanReferenceId,
            @RequestBody Flux<DataBuffer> cleanFileStream) {
        
        logger.info("Received scanned file callback for reference: {}", scanReferenceId);
        
        return fileService.handleScannedFile(scanReferenceId, cleanFileStream)
                .then(Mono.just(ResponseEntity.ok("Upload completed")))
                .onErrorReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Upload failed"));
    }
    
    /**
     * Get file status endpoint.
     */
    @GetMapping("/{fileId}/status")
    public Mono<ResponseEntity<FileStatusResponse>> getFileStatus(@PathVariable UUID fileId) {
        logger.info("Getting status for file: {}", fileId);
        
        return fileService.getFileStatus(fileId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }
    
    /**
     * Download file endpoint - streams file from S3.
     */
    @GetMapping("/{fileId}/download")
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadFile(@PathVariable UUID fileId) {
        logger.info("Download request for file: {}", fileId);
        
        return fileService.getFileForDownload(fileId)
                .map(fileEntity -> {
                    Flux<DataBuffer> fileStream = fileService.streamFileContent(fileEntity.s3Key());
                    
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType(fileEntity.contentType()));
                    headers.setContentDispositionFormData("attachment", fileEntity.filename());
                    
                    if (fileEntity.fileSize() != null) {
                        headers.setContentLength(fileEntity.fileSize());
                    }
                    
                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(fileStream);
                })
                .onErrorReturn(ResponseEntity.notFound().build());
    }
    
    private String determineContentType(FilePart filePart) {
        HttpHeaders headers = filePart.headers();
        MediaType contentType = headers.getContentType();
        return contentType != null ? contentType.toString() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}