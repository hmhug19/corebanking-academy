-- Shows how far a video conversion is: the media worker writes the percent of the playable MP4 it has made, and
-- GET /api/v1/learning-assets/uploads/{uploadId} returns it as processingPercent while the asset is processing.
-- Run before the new backend (it validates this column at startup). Safe to run again.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-17-media-processing-progress.sql
-- Rollback: docs/sql/upgrade/2026-11-17-media-processing-progress.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assets
    ADD COLUMN IF NOT EXISTS processing_progress integer
        CHECK (processing_progress IS NULL OR processing_progress BETWEEN 0 AND 100);

COMMENT ON COLUMN learning_hub.assets.processing_progress IS
    'Phần trăm chuyển mã video của lần xử lý hiện tại (0-99); null khi chưa chuyển mã hoặc không cần.';

COMMIT;
