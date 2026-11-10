-- Rollback of 2026-11-11-knowledge-document-scope.sql. Drops the scope columns, so every document becomes visible to
-- everyone again, including personal and unit documents. Deploy a backend without document scope first.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-11-knowledge-document-scope.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP INDEX IF EXISTS learning_hub.idx_knowledge_documents_scope;
ALTER TABLE learning_hub.knowledge_documents DROP CONSTRAINT IF EXISTS ck_knowledge_documents_scope;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS scope_org_id;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS scope;

COMMIT;
