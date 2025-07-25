package io.filemanager.filez.model;

public enum FileStatus {
    UPLOADING,    // Initial upload received, metadata saved
    SCANNING,     // Sent to AVScan service  
    CLEAN,        // Scan completed, file is clean and stored in S3
    INFECTED,     // File contains malware
    FAILED        // Upload or scan failed
}