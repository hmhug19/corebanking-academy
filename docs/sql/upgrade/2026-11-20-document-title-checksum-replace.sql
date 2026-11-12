-- Document management (rag-quality ticket 08): a document has a title the owner can change (defaults to the file
-- name), the SHA-256 of its file (to refuse an identical upload), and room for one pending replacement file.
-- A replacement is queued on the same row: the old file and chunks keep answering until the worker has embedded the new
-- file and swapped the chunks in one transaction; a failed replacement leaves the old version and shows the error.
-- checksum_sha256 is null for documents uploaded before this script; the backend fills it at start-up from the stored
-- file (one pass, nothing to run by hand).
-- Run once on a DB that already has 2026-11-19-chunker-v2-columns.sql. Safe to run again.
-- The backend refuses to start without these columns: run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-20-document-title-checksum-replace.sql
-- Rollback: docs/sql/upgrade/2026-11-20-document-title-checksum-replace.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS title varchar(255);
UPDATE learning_hub.knowledge_documents SET title = file_name WHERE title IS NULL;
ALTER TABLE learning_hub.knowledge_documents ALTER COLUMN title SET NOT NULL;

ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS checksum_sha256 varchar(64);
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_status varchar(20)
    CHECK (replacement_status IN ('queued', 'processing', 'failed'));
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_error varchar(500);
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_storage_key varchar(100);
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_file_name varchar(255);
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_mime_type varchar(100);
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_size_bytes bigint;
ALTER TABLE learning_hub.knowledge_documents ADD COLUMN IF NOT EXISTS replacement_checksum varchar(64);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_checksum
    ON learning_hub.knowledge_documents(uploaded_by, checksum_sha256) WHERE checksum_sha256 IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_replacement_queue
    ON learning_hub.knowledge_documents(create_time) WHERE replacement_status = 'queued';

COMMENT ON COLUMN learning_hub.knowledge_documents.title IS 'Tên hiển thị và tên trong trích dẫn; mặc định là tên tệp, người tải lên đổi được.';
COMMENT ON COLUMN learning_hub.knowledge_documents.checksum_sha256 IS 'SHA-256 (hex) của tệp hiện tại; null với tài liệu cũ cho tới khi backend tính bù.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_status IS 'Tệp thay thế đang chờ (queued), đang xử lý (processing) hoặc lỗi (failed); null khi không có.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_error IS 'Lý do tiếng Việt khi tệp thay thế lỗi; bản cũ vẫn trả lời.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_storage_key IS 'Tên tệp UUID của bản thay thế; thành storage_key khi đổi bản xong.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_file_name IS 'Tên tệp gốc của bản thay thế.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_mime_type IS 'Loại tệp của bản thay thế.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_size_bytes IS 'Dung lượng của bản thay thế.';
COMMENT ON COLUMN learning_hub.knowledge_documents.replacement_checksum IS 'SHA-256 (hex) của bản thay thế.';

COMMIT;
