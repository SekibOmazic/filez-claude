package io.filemanager.filez.dto;

import io.filemanager.filez.model.FileStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record UploadResponse(
        UUID uploadId,
        UUID fileId,
        String filename,
        FileStatus status,
        String statusUrl,
        LocalDateTime createdAt
) {}