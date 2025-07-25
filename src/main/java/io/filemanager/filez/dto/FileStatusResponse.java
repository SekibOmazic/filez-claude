package io.filemanager.filez.dto;

import io.filemanager.filez.model.FileStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record FileStatusResponse(
        UUID fileId,
        String filename,
        String contentType,
        Long fileSize,
        FileStatus status,
        String downloadUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime scannedAt
) {}