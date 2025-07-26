-- V3__Fix_nullable_columns.sql
-- Fix issues with nullable columns that might be causing R2DBC problems

-- Remove the problematic check constraint that might be interfering
ALTER TABLE files DROP CONSTRAINT IF EXISTS chk_file_size_when_clean;

-- Ensure columns are properly nullable without defaults that might confuse R2DBC
ALTER TABLE files ALTER COLUMN file_size DROP DEFAULT;
ALTER TABLE files ALTER COLUMN scanned_at DROP DEFAULT;

-- Add a simpler constraint that doesn't interfere with inserts
ALTER TABLE files ADD CONSTRAINT chk_clean_files_have_size
    CHECK (status != 'CLEAN' OR (status = 'CLEAN' AND file_size IS NOT NULL));