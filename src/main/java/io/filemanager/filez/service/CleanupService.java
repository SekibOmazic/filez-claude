package io.filemanager.filez.service;

import io.filemanager.filez.config.AppProperties;
import io.filemanager.filez.model.FileStatus;
import io.filemanager.filez.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class CleanupService {
    
    private static final Logger logger = LoggerFactory.getLogger(CleanupService.class);
    
    private final FileRepository fileRepository;
    private final AppProperties appProperties;
    
    public CleanupService(FileRepository fileRepository, AppProperties appProperties) {
        this.fileRepository = fileRepository;
        this.appProperties = appProperties;
    }
    
    /**
     * Scheduled job to cleanup failed/stuck uploads.
     * Runs every 5 minutes to mark old UPLOADING/SCANNING files as FAILED.
     */
    @Scheduled(fixedRateString = "#{@appProperties.upload().tempCleanupInterval().toMillis()}")
    public void cleanupFailedUploads() {
        LocalDateTime cutoffTime = LocalDateTime.now()
                .minus(appProperties.upload().tempCleanupInterval())
                .minusMinutes(10); // Add extra buffer
        
        logger.info("Starting cleanup of failed uploads before: {}", cutoffTime);
        
        // Find and update UPLOADING files that are stuck
        fileRepository.findByStatusAndCreatedAtBefore(FileStatus.UPLOADING, cutoffTime)
                .flatMap(fileEntity -> {
                    logger.warn("Marking stuck UPLOADING file as FAILED: {} (created: {})", 
                               fileEntity.id(), fileEntity.createdAt());
                    return fileRepository.save(fileEntity.withStatus(FileStatus.FAILED));
                })
                .count()
                .flatMap(uploadingCount -> {
                    // Find and update SCANNING files that are stuck
                    return fileRepository.findByStatusAndCreatedAtBefore(FileStatus.SCANNING, cutoffTime)
                            .flatMap(fileEntity -> {
                                logger.warn("Marking stuck SCANNING file as FAILED: {} (created: {})", 
                                           fileEntity.id(), fileEntity.createdAt());
                                return fileRepository.save(fileEntity.withStatus(FileStatus.FAILED));
                            })
                            .count()
                            .map(scanningCount -> uploadingCount + scanningCount);
                })
                .doOnSuccess(totalCleaned -> {
                    if (totalCleaned > 0) {
                        logger.info("Cleanup completed: {} files marked as FAILED", totalCleaned);
                    }
                })
                .doOnError(error -> logger.error("Cleanup job failed: {}", error.getMessage()))
                .onErrorResume(error -> Mono.empty()) // Don't let cleanup errors break the scheduler
                .subscribe();
    }
}