-- Chạy sau khi lùi backend về bản không đọc các cột metadata.
-- Từ chối xóa tên đã đổi hoặc mô tả để tránh mất dữ liệu người dùng.
BEGIN;
LOCK TABLE learning_hub.assets IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM learning_hub.assets
        WHERE (display_name IS NOT NULL AND display_name IS DISTINCT FROM original_name)
           OR description IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Không thể rollback: asset có tên đã thay đổi hoặc mô tả; cần sao lưu và xử lý theo kế hoạch riêng.';
    END IF;
END $$;

ALTER TABLE learning_hub.assets DROP COLUMN IF EXISTS description;
ALTER TABLE learning_hub.assets DROP COLUMN IF EXISTS display_name;
COMMIT;
