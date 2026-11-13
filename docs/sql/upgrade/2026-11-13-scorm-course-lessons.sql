-- SCORM lessons keep runtime state per enrollment and lesson, independently of library previews.
BEGIN;
ALTER TABLE learning_hub.course_lessons ADD COLUMN IF NOT EXISTS scorm_package_id bigint;
ALTER TABLE learning_hub.scorm_registrations ADD COLUMN IF NOT EXISTS enrollment_id bigint;
ALTER TABLE learning_hub.scorm_registrations ADD COLUMN IF NOT EXISTS lesson_id bigint;
ALTER TABLE learning_hub.scorm_registrations
    DROP CONSTRAINT IF EXISTS scorm_registrations_package_id_user_id_preview_key;
CREATE UNIQUE INDEX IF NOT EXISTS ix_scorm_registration_context
    ON learning_hub.scorm_registrations
    (package_id, user_id, preview, (COALESCE(enrollment_id, 0)), (COALESCE(lesson_id, 0)));
COMMENT ON COLUMN learning_hub.course_lessons.scorm_package_id IS 'SCORM package linked to this lesson; logical reference';
COMMENT ON COLUMN learning_hub.scorm_registrations.enrollment_id IS 'NULL for library; course runtime belongs to this enrollment';
UPDATE learning_hub.functions SET name = 'Thư viện bài giảng'
    WHERE code = 'LESSON_MANAGEMENT';
COMMIT;
