package io.filemanager.filez.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Table("files")
public record FileEntity(
        @Id
        UUID id,
        
        @Column("filename")
        String filename,
        
        @Column("content_type")
        String contentType,
        
        @Column("file_size")
        Long fileSize,
        
        @Column("s3_key")
        String s3Key,
        
        @Column("upload_session_id")
        UUID uploadSessionId,
        
        @Column("status")
        FileStatus status,
        
        @Column("scan_reference_id")
        String scanReferenceId,
        
        @Column("created_at")
        LocalDateTime createdAt,
        
        @Column("updated_at")
        LocalDateTime updatedAt,
        
        @Column("scanned_at")
        LocalDateTime scannedAt
) {
    
    public FileEntity withFileSize(Long fileSize) {
        return new FileEntity(
                id, filename, contentType, fileSize, s3Key, uploadSessionId,
                status, scanReferenceId, createdAt, LocalDateTime.now(), scannedAt
        );
    }
    
    public FileEntity withStatus(FileStatus status) {
        return new FileEntity(
                id, filename, contentType, fileSize, s3Key, uploadSessionId,
                status, scanReferenceId, createdAt, LocalDateTime.now(), scannedAt
        );
    }
    
    public FileEntity withScanComplete() {
        return new FileEntity(
                id, filename, contentType, fileSize, s3Key, uploadSessionId,
                FileStatus.CLEAN, scanReferenceId, createdAt, LocalDateTime.now(), LocalDateTime.now()
        );
    }
}