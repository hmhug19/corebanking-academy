-- Rolls back 2026-11-16-knowledge-document-course-source-scope.sql. Course sources become the uploader's personal
-- documents (they would otherwise violate the old constraint); delete them first if they must not show up in the library.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-16-knowledge-document-course-source-scope.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

UPDATE learning_hub.knowledge_documents SET scope = 'personal' WHERE scope = 'course_source';
ALTER TABLE learning_hub.knowledge_documents DROP CONSTRAINT IF EXISTS ck_knowledge_documents_scope;
ALTER TABLE learning_hub.knowledge_documents ADD CONSTRAINT ck_knowledge_documents_scope CHECK (
    scope IN ('personal', 'unit', 'global') AND (scope = 'unit') = (scope_org_id IS NOT NULL));

COMMIT;
