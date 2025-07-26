-- init-db.sql
-- Optional initialization script for PostgreSQL container
-- This ensures the database is ready for Flyway migrations

-- Enable UUID extension (this will be done by Flyway too, but just in case)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Log that initialization completed
DO $$
BEGIN
    RAISE NOTICE 'Database initialization completed successfully';
END $$;