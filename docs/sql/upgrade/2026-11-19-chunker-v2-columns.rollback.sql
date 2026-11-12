-- Rollback of 2026-11-19-chunker-v2-columns.sql. The section path, content hash, chunker version and embed model of
-- every chunk are lost; the chunks themselves stay and keep answering.
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_content_chunks DROP COLUMN IF EXISTS embed_model;
ALTER TABLE learning_hub.course_content_chunks DROP COLUMN IF EXISTS chunker_version;
ALTER TABLE learning_hub.course_content_chunks DROP COLUMN IF EXISTS content_hash;
ALTER TABLE learning_hub.course_content_chunks DROP COLUMN IF EXISTS section_path;
ALTER TABLE learning_hub.knowledge_document_chunks DROP COLUMN IF EXISTS embed_model;
ALTER TABLE learning_hub.knowledge_document_chunks DROP COLUMN IF EXISTS chunker_version;
ALTER TABLE learning_hub.knowledge_document_chunks DROP COLUMN IF EXISTS content_hash;
ALTER TABLE learning_hub.knowledge_document_chunks DROP COLUMN IF EXISTS section_path;

COMMIT;
