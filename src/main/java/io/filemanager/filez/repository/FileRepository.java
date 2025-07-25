package io.filemanager.filez.repository;

import io.filemanager.filez.model.FileEntity;
import io.filemanager.filez.model.FileStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface FileRepository extends ReactiveCrudRepository<FileEntity, UUID> {
    
    Mono<FileEntity> findByScanReferenceId(String scanReferenceId);
    
    @Query("SELECT * FROM files WHERE status = :status AND created_at < :before")
    Flux<FileEntity> findByStatusAndCreatedAtBefore(FileStatus status, LocalDateTime before);
    
    @Query("UPDATE files SET file_size = :fileSize, status = :status, updated_at = :updatedAt WHERE scan_reference_id = :scanReferenceId")
    Mono<Integer> updateFileSizeAndStatusByScanReferenceId(
            String scanReferenceId, 
            Long fileSize, 
            FileStatus status, 
            LocalDateTime updatedAt
    );
}