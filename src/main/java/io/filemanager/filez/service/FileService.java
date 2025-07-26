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
     * Initiates file upload process with enhanced debugging and error handling
     */
    @Transactional
    public Mono<UploadResponse> initiateUpload(UploadRequest request, Flux<DataBuffer> fileStream) {
        UUID uploadSessionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String scanReferenceId = UUID.randomUUID().toString();
        String s3Key = s3Service.generateS3Key(uploadSessionId.toString(), request.filename());

        logger.info("=== UPLOAD INITIATION ===");
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

        logger.info("Entity created: {}", fileEntity);

        return fileRepository.save(fileEntity)
                .doOnNext(saved -> logger.info("✅ Initial entity saved: {}", saved.getId()))
                .doOnError(error -> logger.error("❌ Failed to save initial entity: {}", error.getMessage(), error))
                .flatMap(savedEntity -> {
                    logger.info("Updating status to SCANNING...");

                    // Update status to SCANNING
                    FileEntity updatedEntity = savedEntity.withStatus(FileStatus.SCANNING);
                    return fileRepository.save(updatedEntity);
                })
                .doOnNext(saved -> logger.info("✅ Status updated to SCANNING: {}", saved.getId()))
                .doOnError(error -> logger.error("❌ Failed to update status to SCANNING: {}", error.getMessage(), error))
                .flatMap(savedEntity -> {
                    logger.info("Sending file to AVScan service...");

                    // Send file stream to AVScan service (fire and forget with proper error handling)
                    avScanService.scanFile(fileStream, scanReferenceId, request.filename(), request.contentType())
                            .doOnSuccess(response -> logger.info("✅ File sent to AVScan successfully: {}", scanReferenceId))
                            .doOnError(error -> {
                                logger.error("❌ AVScan service error for {}: {}", scanReferenceId, error.getMessage(), error);

                                // Update status to FAILED in case of AVScan error
                                FileEntity failedEntity = savedEntity.withStatus(FileStatus.FAILED);
                                fileRepository.save(failedEntity)
                                        .doOnSuccess(failed -> logger.info("Status updated to FAILED for: {}", failed.getId()))
                                        .doOnError(saveError -> logger.error("Failed to update status to FAILED: {}", saveError.getMessage()))
                                        .subscribe();
                            })
                            .subscribe(
                                    response -> logger.info("AVScan processing initiated for: {}", scanReferenceId),
                                    error -> logger.error("AVScan subscription error: {}", error.getMessage())
                            );

                    // Return response immediately (async processing)
                    return Mono.just(new UploadResponse(
                            uploadSessionId,
                            savedEntity.getId(),
                            savedEntity.filename(),
                            savedEntity.status(),
                            "/api/v1/files/" + savedEntity.getId() + "/status",
                            savedEntity.createdAt()
                    ));
                })
                .doOnSuccess(response -> logger.info("✅ Upload initiated successfully: {}", response.fileId()))
                .doOnError(error -> logger.error("❌ Upload initiation failed: {}", error.getMessage(), error));
    }

    /**
     * Handles scanned file callback from AVScan service.
     */
    @Transactional
    public Mono<Void> handleScannedFile(String scanReferenceId, Flux<DataBuffer> cleanFileStream) {
        logger.info("=== SCANNED FILE CALLBACK ===");
        logger.info("Scan Reference ID: {}", scanReferenceId);

        return fileRepository.findByScanReferenceId(scanReferenceId)
                .switchIfEmpty(Mono.error(new RuntimeException("File not found for scan reference: " + scanReferenceId)))
                .doOnNext(fileEntity -> logger.info("Found file entity: {} -> {}", scanReferenceId, fileEntity.getId()))
                .flatMap(fileEntity -> {
                    logger.info("Streaming clean content to S3: {}", fileEntity.s3Key());

                    // Stream clean content directly to S3
                    return s3Service.uploadFile(cleanFileStream, fileEntity.s3Key(), fileEntity.contentType())
                            .doOnSuccess(fileSize -> logger.info("✅ S3 upload completed: {} bytes", fileSize))
                            .doOnError(error -> logger.error("❌ S3 upload failed: {}", error.getMessage(), error))
                            .flatMap(fileSize -> {
                                logger.info("Updating file metadata with size: {} bytes", fileSize);

                                // Update file metadata with final size and CLEAN status
                                FileEntity updatedEntity = fileEntity
                                        .withFileSize(fileSize)
                                        .withScanComplete(); // This sets status to CLEAN and scannedAt timestamp

                                return fileRepository.save(updatedEntity);
                            })
                            .doOnSuccess(savedEntity -> logger.info("✅ File processing completed: {}", savedEntity.getId()))
                            .doOnError(error -> logger.error("❌ Failed to update file metadata: {}", error.getMessage(), error));
                })
                .then()
                .doOnSuccess(v -> logger.info("✅ Scanned file callback completed: {}", scanReferenceId))
                .doOnError(error -> logger.error("❌ Scanned file callback failed: {}", error.getMessage(), error));
    }

    /**
     * Gets file status by file ID.
     */
    public Mono<FileStatusResponse> getFileStatus(UUID fileId) {
        logger.debug("Getting status for file: {}", fileId);

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
                ))
                .doOnSuccess(response -> logger.debug("File status retrieved: {}", response.fileId()))
                .doOnError(error -> logger.error("Failed to get file status for {}: {}", fileId, error.getMessage()));
    }

    /**
     * Downloads file by streaming from S3.
     */
    public Mono<FileEntity> getFileForDownload(UUID fileId) {
        logger.info("Getting file for download: {}", fileId);

        return fileRepository.findById(fileId)
                .switchIfEmpty(Mono.error(new RuntimeException("File not found: " + fileId)))
                .filter(fileEntity -> fileEntity.status() == FileStatus.CLEAN)
                .switchIfEmpty(Mono.error(new RuntimeException("File is not available for download")))
                .doOnSuccess(fileEntity -> logger.info("File ready for download: {} ({})",
                        fileEntity.getId(), fileEntity.filename()))
                .doOnError(error -> logger.error("Failed to get file for download {}: {}", fileId, error.getMessage()));
    }

    /**
     * Streams file content from S3.
     */
    public Flux<DataBuffer> streamFileContent(String s3Key) {
        logger.info("Streaming file content from S3: {}", s3Key);
        return s3Service.downloadFile(s3Key);
    }
}