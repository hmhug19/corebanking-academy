-- Apply after 2026-11-12-scorm12.sql and before deploying course SCORM lessons.
BEGIN;
ALTER TABLE learning_hub.course_lessons ADD COLUMN IF NOT EXISTS scorm_package_id bigint;
COMMENT ON COLUMN learning_hub.course_lessons.scorm_package_id IS 'Immutable SCORM package for a scorm lesson; logical relation, no physical FK';
ALTER TABLE learning_hub.scorm_registrations ADD COLUMN IF NOT EXISTS enrollment_id bigint NOT NULL DEFAULT 0;
ALTER TABLE learning_hub.scorm_registrations ADD COLUMN IF NOT EXISTS lesson_id bigint NOT NULL DEFAULT 0;
ALTER TABLE learning_hub.scorm_registrations DROP CONSTRAINT IF EXISTS scorm_registrations_package_id_user_id_preview_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scorm_registration_context
    ON learning_hub.scorm_registrations(package_id, user_id, preview, enrollment_id, lesson_id);
COMMENT ON COLUMN learning_hub.scorm_registrations.enrollment_id IS '0 for independent lectures; otherwise exact enrollment/cycle; never reuse independent runtime state';
COMMENT ON COLUMN learning_hub.scorm_registrations.lesson_id IS '0 for independent lectures; otherwise lesson of the enrollment revision';
COMMIT;
