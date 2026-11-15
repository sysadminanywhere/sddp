-- V5: Add position columns to services table
ALTER TABLE services ADD COLUMN IF NOT EXISTS position_x INTEGER;
ALTER TABLE services ADD COLUMN IF NOT EXISTS position_y INTEGER;