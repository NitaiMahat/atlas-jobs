-- Change payload column from jsonb to text for MVP simplicity
ALTER TABLE jobs
ALTER COLUMN payload TYPE TEXT;
