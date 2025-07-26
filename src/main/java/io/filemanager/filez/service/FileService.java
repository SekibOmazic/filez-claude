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

import java.time.LocalDateTime;
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
     * Initiates file upload process:
     * 1. Save metadata to database with UPLOADING status
     * 2. Stream file content to AVScan service
     * 3. Return upload response immediately (async processing)
     */
    @Transactional
    public Mono<UploadResponse> initiateUpload(UploadRequest request, Flux<DataBuffer> fileStream) {
        UUID uploadSessionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String scanReferenceId = UUID.randomUUID().toString();
        String s3Key = s3Service.generateS3Key(uploadSessionId.toString(), request.filename());

        logger.info("Initiating upload for file: {} (id: {}, session: {})",
                request.filename(), fileId, uploadSessionId);

        // Create file metadata with UPLOADING status - start simple
        FileEntity fileEntity = new FileEntity();
        fileEntity.setId(fileId);
        fileEntity.setFilename(request.filename());
        fileEntity.setContentType(request.contentType());
        fileEntity.setFileSize(null); // null initially
        fileEntity.setS3Key(s3Key);
        fileEntity.setUploadSessionId(uploadSessionId);
        fileEntity.setStatus(FileStatus.UPLOADING);
        fileEntity.setScanReferenceId(scanReferenceId);
        fileEntity.setCreatedAt(LocalDateTime.now());
        fileEntity.setUpdatedAt(LocalDateTime.now());
        fileEntity.setScannedAt(null); // null initially

        logger.info("Saving entity: {}", fileEntity);

        return fileRepository.save(fileEntity)
                .flatMap(savedEntity -> {
                    logger.info("File metadata saved: {}", savedEntity.getId());

                    // Update status to SCANNING using standard save
                    savedEntity.setStatus(FileStatus.SCANNING);
                    savedEntity.setUpdatedAt(LocalDateTime.now());
                    savedEntity.markAsExisting(); // Mark as existing for update

                    return fileRepository.save(savedEntity);
                })
                .flatMap(savedEntity -> {
                    // Send file stream to AVScan service (fire and forget)
                    avScanService.scanFile(fileStream, scanReferenceId, request.filename(), request.contentType())
                            .doOnSuccess(response -> logger.info("File sent to AVScan: {}", scanReferenceId))
                            .doOnError(error -> {
                                logger.error("Failed to send file to AVScan: {}", error.getMessage());
                                // Update status to FAILED
                                savedEntity.setStatus(FileStatus.FAILED);
                                savedEntity.setUpdatedAt(LocalDateTime.now());
                                savedEntity.markAsExisting();
                                fileRepository.save(savedEntity).subscribe();
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
     * Streams clean content directly to S3 and updates metadata.
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

                                // Update entity using standard save
                                fileEntity.setFileSize(fileSize);
                                fileEntity.setStatus(FileStatus.CLEAN);
                                fileEntity.setUpdatedAt(LocalDateTime.now());
                                fileEntity.setScannedAt(LocalDateTime.now());
                                fileEntity.markAsExisting();

                                return fileRepository.save(fileEntity);
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