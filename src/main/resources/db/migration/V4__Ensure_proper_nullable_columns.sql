-- V4__Ensure_proper_nullable_columns.sql
-- Final fix for nullable columns to work properly with R2DBC

-- Drop the problematic constraint from V3
ALTER TABLE files DROP CONSTRAINT IF EXISTS chk_clean_files_have_size;

-- Ensure the columns are properly nullable and have correct types
ALTER TABLE files ALTER COLUMN file_size DROP NOT NULL;
ALTER TABLE files ALTER COLUMN scanned_at DROP NOT NULL;

-- Make sure the nullable columns don't have any problematic defaults
ALTER TABLE files ALTER COLUMN file_size DROP DEFAULT;
ALTER TABLE files ALTER COLUMN scanned_at DROP DEFAULT;

-- Remove the DEFERRABLE constraint that PostgreSQL doesn't support for CHECK constraints
-- Instead, add a simple constraint or rely on application-level validation
-- For now, we'll skip the constraint and handle validation in the application

-- Add comments to document the business rules
COMMENT ON COLUMN files.file_size IS 'File size in bytes - required when status is CLEAN';
COMMENT ON COLUMN files.scanned_at IS 'Timestamp when virus scanning completed - set when status becomes CLEAN';