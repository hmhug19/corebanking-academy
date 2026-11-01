-- Rollback of 2026-11-03-lesson-notes.sql. Drops both tables and with them every learner's notes and bookmarks.
-- It prints how many rows each table holds first, so the loss is visible in the psql output.
-- Deploy a backend without lesson notes first: it fails on startup once the tables are gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-notes.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    note_rows bigint := 0;
    bookmark_rows bigint := 0;
BEGIN
    IF to_regclass('learning_hub.lesson_notes') IS NOT NULL THEN
        SELECT count(*) INTO note_rows FROM learning_hub.lesson_notes;
    END IF;
    IF to_regclass('learning_hub.lesson_bookmarks') IS NOT NULL THEN
        SELECT count(*) INTO bookmark_rows FROM learning_hub.lesson_bookmarks;
    END IF;
    RAISE NOTICE 'Dropping lesson_notes (% rows) and lesson_bookmarks (% rows)', note_rows, bookmark_rows;
END
$report$;

DROP INDEX IF EXISTS learning_hub.idx_lesson_notes_user_lesson;
DROP INDEX IF EXISTS learning_hub.idx_lesson_bookmarks_user_lesson;
DROP TABLE IF EXISTS learning_hub.lesson_notes;
DROP TABLE IF EXISTS learning_hub.lesson_bookmarks;

COMMIT;
