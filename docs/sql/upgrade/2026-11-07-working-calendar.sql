-- Working calendar for the working-day streak: bank-wide holidays and make-up working days.
-- Run once on a DB that already has the Learning baseline (a new DB gets the table from learning_hub_learning.sql).
-- Single transaction: running it twice fails at CREATE TABLE and changes nothing. Seeds the fixed solar holidays of 2026.
-- The streak endpoint reads it with native SQL (no startup validation): run it BEFORE deploying, or the endpoint fails.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-working-calendar.sql
-- Rollback: docs/sql/upgrade/2026-11-07-working-calendar.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE learning_hub.working_calendar_days (
    calendar_date date PRIMARY KEY,
    day_type varchar(20) NOT NULL,
    note varchar(200),
    created_by varchar(20),
    create_time timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_working_calendar_days_type CHECK (day_type IN ('HOLIDAY', 'MAKEUP_WORKDAY'))
);
COMMENT ON TABLE learning_hub.working_calendar_days IS 'Lịch ngày làm việc chung của ngân hàng, chỉ lưu ngoại lệ: HOLIDAY là ngày nghỉ (không tính vào chuỗi ngày học, không làm đứt chuỗi), MAKEUP_WORKDAY là ngày làm bù (thường thứ Bảy, tính là ngày làm việc). Thứ Bảy và Chủ nhật mặc định nghỉ.';
COMMENT ON COLUMN learning_hub.working_calendar_days.calendar_date IS 'Ngày lịch theo Asia/Ho_Chi_Minh.';
COMMENT ON COLUMN learning_hub.working_calendar_days.day_type IS 'HOLIDAY | MAKEUP_WORKDAY.';
COMMENT ON COLUMN learning_hub.working_calendar_days.note IS 'Tên ngày nghỉ hoặc ghi chú ngày làm bù.';
COMMENT ON COLUMN learning_hub.working_calendar_days.created_by IS 'Mã cán bộ thêm ngày; null với ngày gieo sẵn.';
COMMENT ON COLUMN learning_hub.working_calendar_days.create_time IS 'DB sinh thời điểm thêm ngày.';

-- Ngày lễ dương lịch cố định của 2026. Ngày nghỉ Tết Nguyên đán, Giỗ Tổ và ngày làm bù do quản trị nhập theo thông báo.
INSERT INTO learning_hub.working_calendar_days (calendar_date, day_type, note) VALUES
    ('2026-01-01', 'HOLIDAY', 'Tết Dương lịch'),
    ('2026-04-30', 'HOLIDAY', 'Ngày Giải phóng miền Nam'),
    ('2026-05-01', 'HOLIDAY', 'Ngày Quốc tế Lao động'),
    ('2026-09-02', 'HOLIDAY', 'Quốc khánh')
ON CONFLICT (calendar_date) DO NOTHING;

COMMIT;
