-- Chunker v2 (rag-quality ticket 04): every chunk records the section it sits in, a hash of its plain content, the
-- chunker version that cut it and the embedding model that embedded it. The re-index command re-does the chunks whose
-- version or model differs from the configured one. Existing rows are chunker version 1 (line-count chunker) and
-- were embedded with bge-m3; section_path is empty for them until they are re-indexed.
-- section_path joins the headings with ' › '; for a course chunk it is 'chapter › lesson › block' followed by the
-- headings inside the block. The stored content stays the plain text: the title and path are added only when embedding.
-- Run once on a DB that already has 2026-11-18-course-index-coverage.sql. Safe to run again.
-- The backend refuses to start without these columns: run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-19-chunker-v2-columns.sql
-- Rollback: docs/sql/upgrade/2026-11-19-chunker-v2-columns.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.knowledge_document_chunks ADD COLUMN IF NOT EXISTS section_path text NOT NULL DEFAULT '';
ALTER TABLE learning_hub.knowledge_document_chunks ADD COLUMN IF NOT EXISTS content_hash varchar(64) NOT NULL DEFAULT '';
ALTER TABLE learning_hub.knowledge_document_chunks ADD COLUMN IF NOT EXISTS chunker_version smallint NOT NULL DEFAULT 1;
ALTER TABLE learning_hub.knowledge_document_chunks ADD COLUMN IF NOT EXISTS embed_model varchar(100) NOT NULL DEFAULT 'bge-m3';
UPDATE learning_hub.knowledge_document_chunks
   SET content_hash = encode(sha256(convert_to(content, 'UTF8')), 'hex') WHERE content_hash = '';

ALTER TABLE learning_hub.course_content_chunks ADD COLUMN IF NOT EXISTS section_path text NOT NULL DEFAULT '';
ALTER TABLE learning_hub.course_content_chunks ADD COLUMN IF NOT EXISTS content_hash varchar(64) NOT NULL DEFAULT '';
ALTER TABLE learning_hub.course_content_chunks ADD COLUMN IF NOT EXISTS chunker_version smallint NOT NULL DEFAULT 1;
ALTER TABLE learning_hub.course_content_chunks ADD COLUMN IF NOT EXISTS embed_model varchar(100) NOT NULL DEFAULT 'bge-m3';
UPDATE learning_hub.course_content_chunks
   SET content_hash = encode(sha256(convert_to(content, 'UTF8')), 'hex') WHERE content_hash = '';

COMMENT ON COLUMN learning_hub.knowledge_document_chunks.section_path IS 'Đường dẫn mục của đoạn, các đề mục nối bằng '' › ''; rỗng nếu đoạn nằm trước đề mục đầu tiên.';
COMMENT ON COLUMN learning_hub.knowledge_document_chunks.content_hash IS 'SHA-256 (hex) của content, văn bản thuần.';
COMMENT ON COLUMN learning_hub.knowledge_document_chunks.chunker_version IS 'Phiên bản bộ tách đoạn đã tạo đoạn này; 1 = tách theo số dòng, 2 = tách theo mục.';
COMMENT ON COLUMN learning_hub.knowledge_document_chunks.embed_model IS 'Mô hình đã nhúng đoạn; khác cấu hình hiện tại thì cần lập chỉ mục lại.';
COMMENT ON COLUMN learning_hub.course_content_chunks.section_path IS 'Khóa học › chương › bài › khối (và đề mục trong khối), nối bằng '' › ''.';
COMMENT ON COLUMN learning_hub.course_content_chunks.content_hash IS 'SHA-256 (hex) của content, văn bản thuần.';
COMMENT ON COLUMN learning_hub.course_content_chunks.chunker_version IS 'Phiên bản bộ tách đoạn đã tạo đoạn này; 1 = tách theo số dòng, 2 = tách theo mục.';
COMMENT ON COLUMN learning_hub.course_content_chunks.embed_model IS 'Mô hình đã nhúng đoạn; khác cấu hình hiện tại thì cần lập chỉ mục lại.';

COMMIT;
