-- V4__Ensure_proper_nullable_columns.sql
-- Final fix for nullable columns to work properly with R2DBC

-- Drop the problematic constraint
ALTER TABLE files DROP CONSTRAINT IF EXISTS chk_clean_files_have_size;

-- Ensure the columns are properly nullable and have correct types
ALTER TABLE files ALTER COLUMN file_size DROP NOT NULL;
ALTER TABLE files ALTER COLUMN scanned_at DROP NOT NULL;

-- Make sure the nullable columns don't have any problematic defaults
ALTER TABLE files ALTER COLUMN file_size DROP DEFAULT;
ALTER TABLE files ALTER COLUMN scanned_at DROP DEFAULT;

-- Verify table structure is correct
-- file_size should be nullable BIGINT
-- scanned_at should be nullable TIMESTAMP

-- Add a better constraint that doesn't interfere with R2DBC inserts
-- This will only be checked after the row is fully inserted/updated
ALTER TABLE files ADD CONSTRAINT chk_clean_status_requirements
    CHECK (
        status != 'CLEAN' OR
        (status = 'CLEAN' AND file_size IS NOT NULL AND scanned_at IS NOT NULL)
    ) DEFERRABLE INITIALLY DEFERRED;