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

    private String filename;

    @Column("content_type")
    private String contentType;

    @Column("file_size")
    private Long fileSize;

    @Column("s3_key")
    private String s3Key;

    @Column("upload_session_id")
    private UUID uploadSessionId;

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
    private boolean isNewEntity = false;

    // Default constructor for R2DBC
    public FileEntity() {
    }

    // Builder-style factory method that ensures all fields are set
    public static FileEntity forNewUpload(UUID id, String filename, String contentType,
                                          String s3Key, UUID uploadSessionId,
                                          String scanReferenceId) {
        FileEntity entity = new FileEntity();
        LocalDateTime now = LocalDateTime.now();

        entity.id = id;
        entity.filename = filename;
        entity.contentType = contentType;
        entity.fileSize = null; // Must be explicitly null
        entity.s3Key = s3Key;
        entity.uploadSessionId = uploadSessionId;
        entity.status = FileStatus.UPLOADING;
        entity.scanReferenceId = scanReferenceId;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.scannedAt = null; // Must be explicitly null
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

    public void markAsExisting() {
        this.isNewEntity = false;
    }

    // Copy constructor for updates
    private FileEntity(FileEntity source) {
        this.id = source.id;
        this.filename = source.filename;
        this.contentType = source.contentType;
        this.fileSize = source.fileSize;
        this.s3Key = source.s3Key;
        this.uploadSessionId = source.uploadSessionId;
        this.status = source.status;
        this.scanReferenceId = source.scanReferenceId;
        this.createdAt = source.createdAt;
        this.updatedAt = source.updatedAt;
        this.scannedAt = source.scannedAt;
        this.isNewEntity = false;
    }

    public FileEntity withFileSize(Long fileSize) {
        FileEntity updated = new FileEntity(this);
        updated.fileSize = fileSize;
        updated.updatedAt = LocalDateTime.now();
        return updated;
    }

    public FileEntity withStatus(FileStatus status) {
        FileEntity updated = new FileEntity(this);
        updated.status = status;
        updated.updatedAt = LocalDateTime.now();
        return updated;
    }

    public FileEntity withScanComplete() {
        FileEntity updated = new FileEntity(this);
        updated.status = FileStatus.CLEAN;
        updated.updatedAt = LocalDateTime.now();
        updated.scannedAt = LocalDateTime.now();
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

    // Setters - R2DBC requires these for ResultSet mapping
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
        return String.format("FileEntity{id=%s, filename='%s', status=%s, fileSize=%s, scannedAt=%s, isNew=%s}",
                id, filename, status, fileSize, scannedAt, isNewEntity);
    }
}