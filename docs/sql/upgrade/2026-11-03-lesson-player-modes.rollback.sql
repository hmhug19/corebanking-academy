-- Rollback của 2026-11-03-lesson-player-modes.sql. Xóa hai cột và mất mọi mốc video, transcript đã lưu.
-- In ra số khối đang có mốc hoặc transcript trước, để mất mát hiện rõ trong đầu ra của psql.
-- Triển khai backend không còn hai cột này trước: backend hiện tại không khởi động được khi cột đã bị xóa.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-player-modes.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    chapter_blocks bigint := 0;
    transcript_blocks bigint := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'learning_hub'
               AND table_name = 'lesson_blocks' AND column_name = 'video_chapters') THEN
        EXECUTE 'SELECT count(*) FROM learning_hub.lesson_blocks WHERE video_chapters IS NOT NULL'
            INTO chapter_blocks;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'learning_hub'
               AND table_name = 'lesson_blocks' AND column_name = 'transcript') THEN
        EXECUTE 'SELECT count(*) FROM learning_hub.lesson_blocks WHERE transcript IS NOT NULL'
            INTO transcript_blocks;
    END IF;
    RAISE NOTICE 'Dropping video_chapters (% blocks) and transcript (% blocks)', chapter_blocks, transcript_blocks;
END
$report$;

ALTER TABLE learning_hub.lesson_blocks DROP COLUMN IF EXISTS video_chapters;
ALTER TABLE learning_hub.lesson_blocks DROP COLUMN IF EXISTS transcript;

COMMIT;
