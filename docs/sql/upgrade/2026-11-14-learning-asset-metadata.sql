-- Chạy bằng dev_user hoặc chủ sở hữu bảng trên DB đã có baseline Learning.
-- Chỉ thêm metadata; không thay tên file gốc, object S3 hoặc liên kết bài học.
BEGIN;

ALTER TABLE learning_hub.assets ADD COLUMN IF NOT EXISTS display_name varchar(255);
ALTER TABLE learning_hub.assets ADD COLUMN IF NOT EXISTS description varchar(2000);

-- Chỉ điền một lần cho asset chưa có tên, không ghi đè tên đã chỉnh sửa.
UPDATE learning_hub.assets
SET display_name = original_name
WHERE display_name IS NULL;

COMMENT ON COLUMN learning_hub.assets.display_name IS
    'Tên tài nguyên trong thư viện; NULL dùng original_name khi backend cũ tạo asset.';
COMMENT ON COLUMN learning_hub.assets.description IS
    'Mô tả tài nguyên dùng chung, tối đa 2000 ký tự; không chứa nội dung file.';

COMMIT;
