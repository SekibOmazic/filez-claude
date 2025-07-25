package io.filemanager.filez.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UploadRequest(
        @NotBlank(message = "Filename is required")
        String filename,
        
        @NotBlank(message = "Content type is required") 
        String contentType
) {}