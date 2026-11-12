-- Rollback of 2026-11-20-document-title-checksum-replace.sql. Titles, checksums and pending replacements are lost
-- (a pending replacement file stays in the storage folder, unused); documents keep answering under their file name.
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP INDEX IF EXISTS learning_hub.idx_knowledge_documents_replacement_queue;
DROP INDEX IF EXISTS learning_hub.idx_knowledge_documents_checksum;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_checksum;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_size_bytes;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_mime_type;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_file_name;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_storage_key;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_error;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS replacement_status;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS checksum_sha256;
ALTER TABLE learning_hub.knowledge_documents DROP COLUMN IF EXISTS title;

COMMIT;
