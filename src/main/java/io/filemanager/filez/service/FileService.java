package io.filemanager.filez.service;

import io.filemanager.filez.dto.FileStatusResponse;
import io.filemanager.filez.dto.UploadRequest;
import io.filemanager.filez.dto.UploadResponse;
import io.filemanager.filez.model.FileEntity;
import io.filemanager.filez.model.FileStatus;
import io.filemanager.filez.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class FileService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    private final FileRepository fileRepository;
    private final S3Service s3Service;
    private final AVScanService avScanService;

    public FileService(FileRepository fileRepository,
                       S3Service s3Service,
                       AVScanService avScanService) {
        this.fileRepository = fileRepository;
        this.s3Service = s3Service;
        this.avScanService = avScanService;
    }

    /**
     * Initiates file upload process with enhanced debugging
     */
    @Transactional
    public Mono<UploadResponse> initiateUpload(UploadRequest request, Flux<DataBuffer> fileStream) {
        UUID uploadSessionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String scanReferenceId = UUID.randomUUID().toString();
        String s3Key = s3Service.generateS3Key(uploadSessionId.toString(), request.filename());

        logger.info("=== UPLOAD INITIATION DEBUG ===");
        logger.info("File: {} (id: {}, session: {})", request.filename(), fileId, uploadSessionId);
        logger.info("Content-Type: {}", request.contentType());
        logger.info("S3 Key: {}", s3Key);
        logger.info("Scan Reference: {}", scanReferenceId);

        // Create entity using the factory method
        FileEntity fileEntity = FileEntity.forNewUpload(
                fileId,
                request.filename(),
                request.contentType(),
                s3Key,
                uploadSessionId,
                scanReferenceId
        );

        logger.info("=== ENTITY DEBUG ===");
        logger.info("Entity created: {}", fileEntity);
        logger.info("Entity ID: {}", fileEntity.getId());
        logger.info("Entity isNew(): {}", fileEntity.isNew());
        logger.info("Entity fileSize: {}", fileEntity.fileSize());
        logger.info("Entity scannedAt: {}", fileEntity.scannedAt());
        logger.info("Entity status: {}", fileEntity.status());

        // Log all field values
        logger.info("=== ALL ENTITY FIELDS ===");
        logger.info("id: {}", fileEntity.getId());
        logger.info("filename: {}", fileEntity.filename());
        logger.info("contentType: {}", fileEntity.contentType());
        logger.info("fileSize: {}", fileEntity.fileSize());
        logger.info("s3Key: {}", fileEntity.s3Key());
        logger.info("uploadSessionId: {}", fileEntity.uploadSessionId());
        logger.info("status: {}", fileEntity.status());
        logger.info("scanReferenceId: {}", fileEntity.scanReferenceId());
        logger.info("createdAt: {}", fileEntity.createdAt());
        logger.info("updatedAt: {}", fileEntity.updatedAt());
        logger.info("scannedAt: {}", fileEntity.scannedAt());

        return fileRepository.save(fileEntity)
                .doOnNext(saved -> {
                    logger.info("=== SAVE SUCCESS ===");
                    logger.info("Saved entity: {}", saved);
                    logger.info("Saved entity isNew(): {}", saved.isNew());
                })
                .doOnError(error -> {
                    logger.error("=== SAVE FAILED ===");
                    logger.error("Failed to save entity: {}", fileEntity);
                    logger.error("Error details: {}", error.getMessage(), error);

                    // Log the specific SQL error if available
                    if (error.getMessage() != null) {
                        logger.error("SQL Error message: {}", error.getMessage());
                    }

                    Throwable cause = error.getCause();
                    while (cause != null) {
                        logger.error("Caused by: {} - {}", cause.getClass().getSimpleName(), cause.getMessage());
                        cause = cause.getCause();
                    }
                })
                .flatMap(savedEntity -> {
                    logger.info("Initial save completed, updating to SCANNING status");

                    // Update status to SCANNING
                    FileEntity updatedEntity = savedEntity.withStatus(FileStatus.SCANNING);
                    logger.info("Entity for SCANNING update: {}", updatedEntity);

                    return fileRepository.save(updatedEntity);
                })
                .flatMap(savedEntity -> {
                    logger.info("Status updated to SCANNING, sending to AVScan");

                    // Send file stream to AVScan service (fire and forget)
                    avScanService.scanFile(fileStream, scanReferenceId, request.filename(), request.contentType())
                            .doOnSuccess(response -> logger.info("File sent to AVScan: {}", scanReferenceId))
                            .doOnError(error -> {
                                logger.error("Failed to send file to AVScan: {}", error.getMessage());
                                // Update status to FAILED
                                FileEntity failedEntity = savedEntity.withStatus(FileStatus.FAILED);
                                fileRepository.save(failedEntity).subscribe();
                            })
                            .subscribe();

                    // Return response immediately
                    return Mono.just(new UploadResponse(
                            uploadSessionId,
                            savedEntity.getId(),
                            savedEntity.filename(),
                            savedEntity.status(),
                            "/api/v1/files/" + savedEntity.getId() + "/status",
                            savedEntity.createdAt()
                    ));
                });
    }

    /**
     * Handles scanned file callback from AVScan service.
     */
    @Transactional
    public Mono<Void> handleScannedFile(String scanReferenceId, Flux<DataBuffer> cleanFileStream) {
        logger.info("Handling scanned file callback for reference: {}", scanReferenceId);

        return fileRepository.findByScanReferenceId(scanReferenceId)
                .switchIfEmpty(Mono.error(new RuntimeException("File not found for scan reference: " + scanReferenceId)))
                .flatMap(fileEntity -> {
                    logger.info("Found file entity for scan reference: {} -> {}", scanReferenceId, fileEntity.getId());

                    // Stream clean content directly to S3
                    return s3Service.uploadFile(cleanFileStream, fileEntity.s3Key(), fileEntity.contentType())
                            .flatMap(fileSize -> {
                                // Update file metadata with final size and CLEAN status
                                logger.info("S3 upload completed for file: {}, size: {} bytes", fileEntity.getId(), fileSize);

                                // Use helper methods to create updated entity
                                FileEntity updatedEntity = fileEntity
                                        .withFileSize(fileSize)
                                        .withScanComplete(); // This sets status to CLEAN and scannedAt timestamp

                                return fileRepository.save(updatedEntity);
                            });
                })
                .doOnSuccess(savedEntity -> logger.info("File upload completed: {}", savedEntity.getId()))
                .doOnError(error -> logger.error("Failed to handle scanned file: {}", error.getMessage()))
                .then();
    }

    /**
     * Gets file status by file ID.
     */
    public Mono<FileStatusResponse> getFileStatus(UUID fileId) {
        return fileRepository.findById(fileId)
                .switchIfEmpty(Mono.error(new RuntimeException("File not found: " + fileId)))
                .map(fileEntity -> new FileStatusResponse(
                        fileEntity.getId(),
                        fileEntity.filename(),
                        fileEntity.contentType(),
                        fileEntity.fileSize(),
                        fileEntity.status(),
                        fileEntity.status() == FileStatus.CLEAN ?
                                "/api/v1/files/" + fileEntity.getId() + "/download" : null,
                        fileEntity.createdAt(),
                        fileEntity.updatedAt(),
                        fileEntity.scannedAt()
                ));
    }

    /**
     * Downloads file by streaming from S3.
     */
    public Mono<FileEntity> getFileForDownload(UUID fileId) {
        return fileRepository.findById(fileId)
                .switchIfEmpty(Mono.error(new RuntimeException("File not found: " + fileId)))
                .filter(fileEntity -> fileEntity.status() == FileStatus.CLEAN)
                .switchIfEmpty(Mono.error(new RuntimeException("File is not available for download")));
    }

    /**
     * Streams file content from S3.
     */
    public Flux<DataBuffer> streamFileContent(String s3Key) {
        return s3Service.downloadFile(s3Key);
    }
}