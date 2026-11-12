-- Knowledge document scope "course_source": the source file of a course generation job. Only its uploader reads it; it is
-- never listed in /assistant and never found by a search (every visibility query names the scopes it allows).
-- Run once on a DB that already has 2026-11-11-knowledge-document-scope.sql. Safe to run again.
-- The backend refuses to save such a document without this change (the scope check constraint): run it BEFORE deploying.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-16-knowledge-document-course-source-scope.sql
-- Rollback: docs/sql/upgrade/2026-11-16-knowledge-document-course-source-scope.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

-- 'course_source' is 13 characters; the column was varchar(10).
ALTER TABLE learning_hub.knowledge_documents ALTER COLUMN scope TYPE varchar(20);
ALTER TABLE learning_hub.knowledge_documents DROP CONSTRAINT IF EXISTS ck_knowledge_documents_scope;
ALTER TABLE learning_hub.knowledge_documents ADD CONSTRAINT ck_knowledge_documents_scope CHECK (
    scope IN ('personal', 'unit', 'global', 'course_source') AND (scope = 'unit') = (scope_org_id IS NOT NULL));

COMMENT ON COLUMN learning_hub.knowledge_documents.scope IS
    'Ai tìm thấy tài liệu: personal (chỉ người tải lên), unit (đơn vị scope_org_id và đơn vị con), global (mọi người), course_source (tệp nguồn của job tạo khóa học: chỉ người tải lên đọc, không hiện trong danh sách và không được tìm). Tài liệu cũ là global.';

COMMIT;
