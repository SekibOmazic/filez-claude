-- V5__Convert_enum_to_varchar.sql
-- Convert file_status enum to VARCHAR for better R2DBC compatibility

-- First, create a temporary column with VARCHAR type
ALTER TABLE files ADD COLUMN status_temp VARCHAR(20);

-- Copy existing enum values to the temporary column
UPDATE files SET status_temp = status::text;

-- Drop the old enum column
ALTER TABLE files DROP COLUMN status;

-- Rename the temporary column to the original name
ALTER TABLE files RENAME COLUMN status_temp TO status;

-- Set NOT NULL constraint and default value
ALTER TABLE files ALTER COLUMN status SET NOT NULL;
ALTER TABLE files ALTER COLUMN status SET DEFAULT 'UPLOADING';

-- Add a CHECK constraint to ensure only valid values
ALTER TABLE files ADD CONSTRAINT chk_status_values
    CHECK (status IN ('UPLOADING', 'SCANNING', 'CLEAN', 'INFECTED', 'FAILED'));

-- Drop the enum type (it's no longer needed)
DROP TYPE IF EXISTS file_status;

-- Recreate the index on status
DROP INDEX IF EXISTS idx_files_status;
CREATE INDEX idx_files_status ON files(status);

-- Add comment for documentation
COMMENT ON COLUMN files.status IS 'File processing status: UPLOADING, SCANNING, CLEAN, INFECTED, or FAILED';