-- Upgrade event_publication from Spring Modulith v1 schema (created in V3) to v2 schema.
-- Spring Modulith 2.0.x added status, completion_attempts, and last_resubmission_date
-- for dead-letter tracking and retry analytics.
ALTER TABLE event_publication
    ADD COLUMN IF NOT EXISTS status                 TEXT,
    ADD COLUMN IF NOT EXISTS completion_attempts    INT,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMPTZ;
