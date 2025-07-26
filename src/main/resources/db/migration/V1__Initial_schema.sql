-- V1__Initial_schema.sql
-- Flyway migration for file management system

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- File status enum
CREATE TYPE file_status AS ENUM ('UPLOADING', 'SCANNING', 'CLEAN', 'INFECTED', 'FAILED');

-- Files table for metadata storage
CREATE TABLE files (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT,
    s3_key VARCHAR(500) NOT NULL,
    upload_session_id UUID NOT NULL,
    status file_status NOT NULL DEFAULT 'UPLOADING',
    scan_reference_id VARCHAR(255) UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    scanned_at TIMESTAMP
);

-- Indexes for performance
CREATE INDEX idx_files_status ON files(status);
CREATE INDEX idx_files_scan_reference_id ON files(scan_reference_id);
CREATE INDEX idx_files_upload_session_id ON files(upload_session_id);
CREATE INDEX idx_files_created_at ON files(created_at);
CREATE INDEX idx_files_status_created_at ON files(status, created_at);

-- Function for updating updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger
CREATE TRIGGER update_files_updated_at
    BEFORE UPDATE ON files
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();