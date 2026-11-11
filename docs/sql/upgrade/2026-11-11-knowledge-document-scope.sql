-- Knowledge document scope: who may find a document in assistant answers and lists.
-- personal = only the uploader, unit = the uploader's unit and its child units, global = everyone.
-- Documents that exist today were shared with everyone, so they become global. New uploads always state a scope.
-- Run once on a DB that already has the Knowledge baseline (learning_hub_knowledge.sql). Safe to run again.
-- The backend refuses to start without these columns (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-11-knowledge-document-scope.sql
-- Rollback: docs/sql/upgrade/2026-11-11-knowledge-document-scope.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.knowledge_documents
    ADD COLUMN IF NOT EXISTS scope varchar(10) NOT NULL DEFAULT 'global',
    ADD COLUMN IF NOT EXISTS scope_org_id bigint;
-- The default only migrated the rows above; later inserts must choose.
ALTER TABLE learning_hub.knowledge_documents ALTER COLUMN scope DROP DEFAULT;

DO $constraint$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_knowledge_documents_scope') THEN
        ALTER TABLE learning_hub.knowledge_documents ADD CONSTRAINT ck_knowledge_documents_scope CHECK (
            scope IN ('personal', 'unit', 'global') AND (scope = 'unit') = (scope_org_id IS NOT NULL));
    END IF;
END
$constraint$;

COMMENT ON COLUMN learning_hub.knowledge_documents.scope IS
    'Ai tìm thấy tài liệu: personal (chỉ người tải lên), unit (đơn vị scope_org_id và đơn vị con), global (mọi người). Tài liệu cũ là global.';
COMMENT ON COLUMN learning_hub.knowledge_documents.scope_org_id IS
    'Đơn vị của người tải lên lúc tải (orgs.org_id, ID logic không FK); chỉ có khi scope = unit.';

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_scope
    ON learning_hub.knowledge_documents(scope, scope_org_id);

COMMIT;
