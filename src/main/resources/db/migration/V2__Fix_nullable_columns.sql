-- Ensure nullable columns have explicit defaults
ALTER TABLE files ALTER COLUMN file_size SET DEFAULT NULL;
ALTER TABLE files ALTER COLUMN scanned_at SET DEFAULT NULL;

-- Add check to ensure data integrity
ALTER TABLE files ADD CONSTRAINT chk_file_size_when_clean
    CHECK (status != 'CLEAN' OR file_size IS NOT NULL);