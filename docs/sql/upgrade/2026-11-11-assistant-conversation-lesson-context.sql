-- Assistant conversations opened from a lesson: the lesson the quick chat was opened on, so reopening the
-- conversation shows what it was about and a follow-up keeps asking about the same lesson.
-- Run once on a DB that already has 2026-11-10-assistant-conversations.sql. Safe to run again.
-- The backend refuses to start without these columns (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-11-assistant-conversation-lesson-context.sql
-- Rollback: docs/sql/upgrade/2026-11-11-assistant-conversation-lesson-context.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_conversations ADD COLUMN IF NOT EXISTS context_course_id bigint;
ALTER TABLE learning_hub.assistant_conversations ADD COLUMN IF NOT EXISTS context_revision_id bigint;
ALTER TABLE learning_hub.assistant_conversations ADD COLUMN IF NOT EXISTS context_lesson_id bigint;
ALTER TABLE learning_hub.assistant_conversations ADD COLUMN IF NOT EXISTS context_label varchar(255);

DO $constraint$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_assistant_conversations_context') THEN
        ALTER TABLE learning_hub.assistant_conversations ADD CONSTRAINT ck_assistant_conversations_context CHECK (
            (context_course_id IS NULL) = (context_revision_id IS NULL)
            AND (context_course_id IS NULL) = (context_lesson_id IS NULL)
            AND (context_course_id IS NULL) = (context_label IS NULL));
    END IF;
END
$constraint$;

COMMENT ON COLUMN learning_hub.assistant_conversations.context_course_id IS 'Khóa học của bài học mà hội thoại được mở từ đó (courses.course_id, ID logic không FK); null nếu hội thoại không gắn bài học.';
COMMENT ON COLUMN learning_hub.assistant_conversations.context_revision_id IS 'Revision của bài học lúc mở hội thoại (course_revisions.revision_id).';
COMMENT ON COLUMN learning_hub.assistant_conversations.context_lesson_id IS 'Bài học mà hội thoại được mở từ đó (course_lessons.lesson_id).';
COMMENT ON COLUMN learning_hub.assistant_conversations.context_label IS 'Nhãn hiển thị "Khóa › Bài" lúc mở hội thoại, để hiện lại mà không cần đọc lại bài học.';

COMMIT;
