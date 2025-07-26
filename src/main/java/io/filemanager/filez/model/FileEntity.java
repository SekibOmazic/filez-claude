package io.filemanager.filez.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Table("files")
public class FileEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("filename")
    private String filename;

    @Column("content_type")
    private String contentType;

    @Column("file_size")
    private Long fileSize;

    @Column("s3_key")
    private String s3Key;

    @Column("upload_session_id")
    private UUID uploadSessionId;

    @Column("status")
    private FileStatus status;

    @Column("scan_reference_id")
    private String scanReferenceId;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("scanned_at")
    private LocalDateTime scannedAt;

    @Transient
    private boolean isNewEntity = true;

    // Default constructor for R2DBC
    public FileEntity() {}

    // Constructor for creating new entities
    public FileEntity(UUID id, String filename, String contentType, Long fileSize,
                      String s3Key, UUID uploadSessionId, FileStatus status,
                      String scanReferenceId, LocalDateTime createdAt,
                      LocalDateTime updatedAt, LocalDateTime scannedAt) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.fileSize = fileSize; // Can be null
        this.s3Key = s3Key;
        this.uploadSessionId = uploadSessionId;
        this.status = status;
        this.scanReferenceId = scanReferenceId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.scannedAt = scannedAt; // Can be null
        this.isNewEntity = true; // New entity by default
    }

    // Static factory method for new upload (avoids null issues)
    public static FileEntity forNewUpload(UUID id, String filename, String contentType,
                                          String s3Key, UUID uploadSessionId,
                                          String scanReferenceId) {
        LocalDateTime now = LocalDateTime.now();
        FileEntity entity = new FileEntity();
        entity.id = id;
        entity.filename = filename;
        entity.contentType = contentType;
        entity.fileSize = null; // Explicitly null for new uploads
        entity.s3Key = s3Key;
        entity.uploadSessionId = uploadSessionId;
        entity.status = FileStatus.UPLOADING;
        entity.scanReferenceId = scanReferenceId;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.scannedAt = null; // Explicitly null until scanned
        entity.isNewEntity = true;
        return entity;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }

    // Mark as existing entity (for updates)
    public void markAsExisting() {
        this.isNewEntity = false;
    }

    // Helper methods for updates
    public FileEntity withFileSize(Long fileSize) {
        FileEntity updated = new FileEntity(
                this.id, this.filename, this.contentType, fileSize, this.s3Key,
                this.uploadSessionId, this.status, this.scanReferenceId,
                this.createdAt, LocalDateTime.now(), this.scannedAt
        );
        updated.isNewEntity = false; // Mark as existing for update
        return updated;
    }

    public FileEntity withStatus(FileStatus status) {
        FileEntity updated = new FileEntity(
                this.id, this.filename, this.contentType, this.fileSize, this.s3Key,
                this.uploadSessionId, status, this.scanReferenceId,
                this.createdAt, LocalDateTime.now(), this.scannedAt
        );
        updated.isNewEntity = false; // Mark as existing for update
        return updated;
    }

    public FileEntity withScanComplete() {
        FileEntity updated = new FileEntity(
                this.id, this.filename, this.contentType, this.fileSize, this.s3Key,
                this.uploadSessionId, FileStatus.CLEAN, this.scanReferenceId,
                this.createdAt, LocalDateTime.now(), LocalDateTime.now()
        );
        updated.isNewEntity = false; // Mark as existing for update
        return updated;
    }

    // Getters
    public String filename() { return filename; }
    public String contentType() { return contentType; }
    public Long fileSize() { return fileSize; }
    public String s3Key() { return s3Key; }
    public UUID uploadSessionId() { return uploadSessionId; }
    public FileStatus status() { return status; }
    public String scanReferenceId() { return scanReferenceId; }
    public LocalDateTime createdAt() { return createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }
    public LocalDateTime scannedAt() { return scannedAt; }

    // Setters (needed by R2DBC)
    public void setId(UUID id) { this.id = id; }
    public void setFilename(String filename) { this.filename = filename; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }
    public void setUploadSessionId(UUID uploadSessionId) { this.uploadSessionId = uploadSessionId; }
    public void setStatus(FileStatus status) { this.status = status; }
    public void setScanReferenceId(String scanReferenceId) { this.scanReferenceId = scanReferenceId; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setScannedAt(LocalDateTime scannedAt) { this.scannedAt = scannedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileEntity that = (FileEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "FileEntity{" +
                "id=" + id +
                ", filename='" + filename + '\'' +
                ", status=" + status +
                ", isNew=" + isNewEntity +
                '}';
    }
}