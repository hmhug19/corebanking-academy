-- CORE Academy - Mở rộng media và tiến độ v2 rút gọn (thiết kế, không tự chạy).
-- Bản chuẩn: docs/sql/learning_hub_media_progress.sql.
-- Chạy sau learning_hub_identity.sql và learning_hub_learning.sql (26 bảng Learning).
-- Thêm 2 bảng: asset_upload_parts và learning_user_leases. Không FK vật lý, không seed/reset dữ liệu.
-- Giữ các cột storage_provider/block lease v1 để tương thích; đánh dấu ngừng dùng.
-- DB giữ PK/UNIQUE/NOT NULL và giới hạn số cơ bản; lifecycle/checksum/session do backend kiểm.
-- Script gỡ CHECK nghiệp vụ cũ của v1/v2 theo tên, không xóa dữ liệu hoặc nới khóa chống trùng.
-- Thay một partial index để sửa casing v1; preflight chỉ chạy khi thực thi script.
-- Không DROP TABLE/COLUMN hoặc tự backfill. Kiểm và backup dữ liệu hiện hữu trước khi chạy.
-- DDL không triển khai worker, multipart S3, phân quyền, ký media hoặc kiểm heartbeat.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- Dừng nếu casing trạng thái không tương thích; không tự sửa dữ liệu cũ.
DO $preflight$
DECLARE
    table_name text;
    has_mixed_status boolean;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['assets', 'course_assignments', 'assignment_recipients',
                                     'course_enrollments', 'lesson_progress', 'block_progress'] LOOP
        EXECUTE format('select exists(select 1 from learning_hub.%I where status <> lower(status))', table_name)
            INTO has_mixed_status;
        IF has_mixed_status THEN
            RAISE EXCEPTION 'Status casing in learning_hub.% is incompatible; review/backfill explicitly first',
                table_name;
        END IF;
    END LOOP;
END
$preflight$;

-- 01. Dùng lại assets làm sổ tài nguyên chuẩn; bytes lâu dài lưu tại AIStor/S3 private.
ALTER TABLE learning_hub.assets
    ADD COLUMN IF NOT EXISTS media_type varchar(20),
    ADD COLUMN IF NOT EXISTS playback_key varchar(500),
    ADD COLUMN IF NOT EXISTS playback_size_bytes bigint,
    ADD COLUMN IF NOT EXISTS playback_mime_type varchar(100),
    ADD COLUMN IF NOT EXISTS playback_checksum_sha256 varchar(64),
    ADD COLUMN IF NOT EXISTS upload_id uuid,
    ADD COLUMN IF NOT EXISTS s3_upload_id text,
    ADD COLUMN IF NOT EXISTS upload_total_bytes bigint,
    ADD COLUMN IF NOT EXISTS uploaded_bytes bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS chunk_size_bytes integer NOT NULL DEFAULT 8388608,
    ADD COLUMN IF NOT EXISTS upload_expected_sha256 varchar(64),
    ADD COLUMN IF NOT EXISTS upload_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS upload_completed_at timestamptz,
    ADD COLUMN IF NOT EXISTS processing_error text,
    ADD COLUMN IF NOT EXISTS processing_error_code varchar(50),
    ADD COLUMN IF NOT EXISTS processing_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS processing_lease_id uuid,
    ADD COLUMN IF NOT EXISTS processing_lease_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS media_checked_at timestamptz,
    ADD COLUMN IF NOT EXISTS page_count_checked_at timestamptz,
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz,
    ADD COLUMN IF NOT EXISTS is_deleted boolean NOT NULL DEFAULT false;

-- v1 tạo cột kho thứ hai mặc định LOCAL, kể cả khi storage_type=s3.
-- Giữ giá trị để review; code mới chỉ dùng storage_type. Không backfill bằng suy đoán.
DO $compatibility$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'learning_hub' AND table_name = 'assets' AND column_name = 'storage_provider'
    ) THEN
        ALTER TABLE learning_hub.assets ALTER COLUMN storage_provider DROP NOT NULL;
        ALTER TABLE learning_hub.assets ALTER COLUMN storage_provider DROP DEFAULT;
        COMMENT ON COLUMN learning_hub.assets.storage_provider IS
            'Cột v1 đã ngừng dùng, có thể mang LOCAL không đúng thực tế. Nguồn chuẩn là storage_type; không dùng để phân quyền hoặc fallback.';
    END IF;
END
$compatibility$;

-- Gỡ CHECK lifecycle/format của v1/v2, kể cả khi đã tồn tại trên DB.
-- Service phải kiểm ready, rendition, hash, processing lease và xóa mềm trong use case tương ứng.
ALTER TABLE learning_hub.assets
    DROP CONSTRAINT IF EXISTS ck_assets_ready_key,
    DROP CONSTRAINT IF EXISTS ck_assets_media_v2,
    DROP CONSTRAINT IF EXISTS ck_assets_playback_v2,
    DROP CONSTRAINT IF EXISTS ck_assets_checksums_v2,
    DROP CONSTRAINT IF EXISTS ck_assets_ready_upload_v2,
    DROP CONSTRAINT IF EXISTS ck_assets_processing_lease_v2,
    DROP CONSTRAINT IF EXISTS ck_assets_deletion_v2;

ALTER TABLE learning_hub.assets
    DROP CONSTRAINT IF EXISTS ck_assets_size,
    ADD CONSTRAINT ck_assets_size CHECK (
        size_bytes >= 0 AND uploaded_bytes >= 0 AND chunk_size_bytes > 0
        AND (upload_total_bytes IS NULL OR upload_total_bytes > 0)
    ),
    DROP CONSTRAINT IF EXISTS ck_assets_media_dimensions,
    ADD CONSTRAINT ck_assets_media_dimensions CHECK (
        (duration_seconds IS NULL OR duration_seconds >= 0)
        AND (page_count IS NULL OR page_count >= 1)
        AND (width IS NULL OR width > 0) AND (height IS NULL OR height > 0)
    ),
    DROP CONSTRAINT IF EXISTS ck_assets_playback_size,
    ADD CONSTRAINT ck_assets_playback_size CHECK (
        playback_size_bytes IS NULL OR playback_size_bytes > 0
    ),
    DROP CONSTRAINT IF EXISTS ck_assets_processing_attempts,
    ADD CONSTRAINT ck_assets_processing_attempts CHECK (processing_attempts >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS ux_assets_upload_id ON learning_hub.assets(upload_id)
    WHERE upload_id IS NOT NULL;
-- Thay predicate viết hoa của v1; IF NOT EXISTS sẽ giữ nguyên định nghĩa sai.
DROP INDEX IF EXISTS learning_hub.ix_assets_upload_cleanup;
CREATE INDEX ix_assets_upload_cleanup ON learning_hub.assets(upload_expires_at)
    WHERE status IN ('uploading', 'processing');
CREATE INDEX IF NOT EXISTS ix_assets_file_checksum ON learning_hub.assets(checksum_sha256)
    WHERE checksum_sha256 IS NOT NULL AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_assets_owner_media ON learning_hub.assets(owner_org_id, media_type, status)
    WHERE NOT is_deleted;

COMMENT ON TABLE learning_hub.assets IS
    'Sổ tài nguyên dùng chung: file gốc bất biến theo asset_id, bytes lưu S3 private. Thay file bằng asset/revision mới, dùng lại bằng tham chiếu.';
COMMENT ON COLUMN learning_hub.assets.storage_type IS 'Kho lưu chuẩn; triển khai hiện tại dùng s3, không fallback local.';
COMMENT ON COLUMN learning_hub.assets.media_type IS 'video/document/image/attachment; NULL của dữ liệu cũ cần backfill có kiểm chứng.';
COMMENT ON COLUMN learning_hub.assets.playback_key IS 'Key rendition faststart/remux trong S3 private; NULL dùng bản gốc. Không phải URL công khai.';
COMMENT ON COLUMN learning_hub.assets.playback_size_bytes IS 'Kích thước thật của rendition để trả Range/Content-Length; không lấy size_bytes bản gốc.';
COMMENT ON COLUMN learning_hub.assets.playback_mime_type IS 'MIME của rendition, độc lập với mime_type bản gốc.';
COMMENT ON COLUMN learning_hub.assets.playback_checksum_sha256 IS 'SHA-256 rendition đã được server kiểm chứng; giữ nguyên checksum của bản gốc.';
COMMENT ON COLUMN learning_hub.assets.upload_id IS 'UUID phiên upload ứng dụng, chỉ trả cho người có quyền; khác s3_upload_id của provider.';
COMMENT ON COLUMN learning_hub.assets.s3_upload_id IS 'ID multipart nội bộ của S3; không trả ID provider hoặc credential cho FE.';
COMMENT ON COLUMN learning_hub.assets.upload_total_bytes IS 'Số byte dự kiến; đối soát với các part đã commit và file gốc sau complete.';
COMMENT ON COLUMN learning_hub.assets.uploaded_bytes IS 'SUM size của các part đã commit duy nhất; không tăng theo số request và không thay thế manifest.';
COMMENT ON COLUMN learning_hub.assets.chunk_size_bytes IS 'Mặc định 8 MiB; mọi part trừ phần cuối phải đủ kích thước này. BE kiểm số phần và size.';
COMMENT ON COLUMN learning_hub.assets.upload_expected_sha256 IS 'SHA-256 toàn file khai báo để chọn lại/resume đúng file; BE kiểm file gốc trước ready.';
COMMENT ON COLUMN learning_hub.assets.upload_expires_at IS 'Hạn phiên upload do ứng dụng quản lý; cột này không tự làm multipart S3 hết hạn.';
COMMENT ON COLUMN learning_hub.assets.upload_completed_at IS 'Đã ACK/đối soát complete S3 và kích thước file gốc; chưa đồng nghĩa ready.';
COMMENT ON COLUMN learning_hub.assets.processing_error IS 'Tóm tắt lỗi an toàn cho vận hành; không trả command, path, credential hoặc exception thô cho học viên.';
COMMENT ON COLUMN learning_hub.assets.processing_error_code IS 'Lý do ổn định như UNSUPPORTED_CODEC/CHECKSUM_MISMATCH/STORAGE_UNAVAILABLE.';
COMMENT ON COLUMN learning_hub.assets.processing_attempts IS 'Số lần worker claim; BE giới hạn retry và tài nguyên xử lý.';
COMMENT ON COLUMN learning_hub.assets.processing_lease_id IS 'UUID lượt claim worker; hoàn tất bằng compare-and-set, worker cũ không được publish ready.';
COMMENT ON COLUMN learning_hub.assets.processing_lease_expires_at IS 'Hạn phục hồi khi worker chết; không giữ DB lock trong IO S3/ffprobe/ffmpeg.';
COMMENT ON COLUMN learning_hub.assets.media_checked_at IS 'Thời điểm server thực sự kiểm file/probe; không tự điền cho legacy chưa được kiểm chứng.';
COMMENT ON COLUMN learning_hub.assets.page_count_checked_at IS 'Thời điểm server kiểm số trang PDF bằng parser.';
COMMENT ON COLUMN learning_hub.assets.is_deleted IS 'Cờ xóa mềm; BE kiểm mọi tham chiếu và serialize attach/delete trước cleanup theo retention.';
COMMENT ON COLUMN learning_hub.assets.deleted_at IS 'Thời điểm xóa mềm; DDL không tự xóa file.';

-- 02. Manifest upload bền vững qua reconnect browser và restart backend.
CREATE TABLE IF NOT EXISTS learning_hub.asset_upload_parts (
    upload_id uuid NOT NULL,
    part_number integer NOT NULL,
    size_bytes bigint NOT NULL,
    checksum_sha256 varchar(64) NOT NULL,
    part_status varchar(12) NOT NULL DEFAULT 'uploading',
    etag varchar(255),
    committed_at timestamptz,
    created_by varchar(20) NOT NULL,
    updated_by varchar(20) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    create_time timestamptz NOT NULL DEFAULT now(),
    update_time timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);
-- Đây là tên PostgreSQL sinh cho các CHECK inline/không đặt tên trong v2 đã bàn giao.
-- Chỉ gỡ các CHECK đã biết của extension; không quét/xóa constraint tùy ý trong schema.
ALTER TABLE learning_hub.asset_upload_parts
    DROP CONSTRAINT IF EXISTS asset_upload_parts_checksum_sha256_check,
    DROP CONSTRAINT IF EXISTS asset_upload_parts_part_status_check,
    DROP CONSTRAINT IF EXISTS asset_upload_parts_check,
    DROP CONSTRAINT IF EXISTS asset_upload_parts_part_number_check,
    ADD CONSTRAINT asset_upload_parts_part_number_check CHECK (part_number >= 1),
    DROP CONSTRAINT IF EXISTS asset_upload_parts_size_bytes_check,
    ADD CONSTRAINT asset_upload_parts_size_bytes_check CHECK (size_bytes > 0);
COMMENT ON TABLE learning_hub.asset_upload_parts IS 'Manifest bền vững; một upload UUID + part number có một danh tính/hash bất biến. Không lưu bytes file trong DB.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.upload_id IS 'Tham chiếu logic assets.upload_id, không FK. Service kiểm owner, trạng thái và thời hạn.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.part_number IS 'Số thứ tự S3 từ 1; PK hỗ trợ đọc manifest theo thứ tự khi status/complete.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.size_bytes IS 'Kích thước part đã kiểm; phần không phải cuối phải bằng chunk_size_bytes của asset.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.checksum_sha256 IS 'Hash định danh part bất biến; payload khác trên cùng part number phải bị từ chối.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.part_status IS 'uploaded chỉ sau ACK provider; uploading là reservation/retry, chưa cộng uploaded_bytes.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.etag IS 'ETag part do provider trả; không thay SHA-256.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.committed_at IS 'Mốc server ACK part; retry không tạo dòng mới.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.created_by IS 'ID người upload đã xác thực; không lấy từ request body.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.updated_by IS 'ID actor hợp lệ cập nhật manifest gần nhất.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.row_version IS 'Optimistic version cho chuyển trạng thái; đối soát DB/provider trước complete.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.create_time IS 'Thời điểm DB tạo dòng.';
COMMENT ON COLUMN learning_hub.asset_upload_parts.update_time IS 'Thời điểm cập nhật do backend quản lý.';

-- 03. Liên kết media chính; attachment giữ tại block_assets. Backfill primary cũ cần review.
ALTER TABLE learning_hub.lesson_blocks
    ADD COLUMN IF NOT EXISTS media_asset_id bigint,
    ADD COLUMN IF NOT EXISTS meta_json jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE learning_hub.lesson_blocks
    DROP CONSTRAINT IF EXISTS ck_lesson_blocks_completion_threshold,
    ADD CONSTRAINT ck_lesson_blocks_completion_threshold CHECK (
        completion_threshold IS NULL OR completion_threshold BETWEEN 0 AND 100
    );
CREATE INDEX IF NOT EXISTS ix_lesson_blocks_media_asset ON learning_hub.lesson_blocks(media_asset_id)
    WHERE media_asset_id IS NOT NULL;
COMMENT ON COLUMN learning_hub.lesson_blocks.media_asset_id IS 'Tham chiếu logic tới file chính bất biến. Đối soát block_assets usage=primary cũ trước cutover.';
COMMENT ON COLUMN learning_hub.lesson_blocks.meta_json IS 'Metadata được kiểm theo block type; không nhận policy tùy ý từ FE.';
COMMENT ON COLUMN learning_hub.lesson_blocks.completion_mode IS 'Mode source hiện tại: manual/video_coverage/pdf_end/open. BE áp policy được phép trên draft.';
COMMENT ON COLUMN learning_hub.lesson_blocks.completion_threshold IS 'Ngưỡng coverage video duy nhất; vị trí tua không phải coverage.';
COMMENT ON TABLE learning_hub.block_assets IS 'Liên kết tài nguyên/attachment/caption. Gỡ liên kết không xóa bytes đang dùng chung.';

-- 04. Resume/coverage theo enrollment và block. Giữ block lease v1 chỉ để tương thích cột.
ALTER TABLE learning_hub.block_progress
    ADD COLUMN IF NOT EXISTS media_asset_id bigint,
    ADD COLUMN IF NOT EXISTS watched_seconds numeric(12,3) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS active_session_id uuid,
    ADD COLUMN IF NOT EXISTS session_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS session_fence bigint NOT NULL DEFAULT 0;
ALTER TABLE learning_hub.block_progress
    DROP CONSTRAINT IF EXISTS ck_block_progress_position,
    ADD CONSTRAINT ck_block_progress_position CHECK (
        (last_video_position_seconds IS NULL OR last_video_position_seconds >= 0)
        AND (last_pdf_page IS NULL OR last_pdf_page >= 1)
    ),
    DROP CONSTRAINT IF EXISTS ck_block_progress_percent,
    ADD CONSTRAINT ck_block_progress_percent CHECK (progress_percent BETWEEN 0 AND 100),
    DROP CONSTRAINT IF EXISTS ck_block_progress_watched,
    ADD CONSTRAINT ck_block_progress_watched CHECK (watched_seconds >= 0),
    DROP CONSTRAINT IF EXISTS ck_block_progress_lease_pair;
CREATE UNIQUE INDEX IF NOT EXISTS ux_block_progress_enrollment_block
    ON learning_hub.block_progress(enrollment_id, block_id);
CREATE INDEX IF NOT EXISTS ix_block_progress_active_lease ON learning_hub.block_progress(session_expires_at)
    WHERE active_session_id IS NOT NULL;
COMMENT ON COLUMN learning_hub.block_progress.media_asset_id IS 'Danh tính file gắn với tiến độ; không tự chuyển resume/coverage sang asset khác.';
COMMENT ON COLUMN learning_hub.block_progress.watched_seconds IS 'Độ dài hợp các khoảng vị trí nội dung được chấp nhận; không phải thời gian học thực theo đồng hồ.';
COMMENT ON COLUMN learning_hub.block_progress.active_session_id IS 'Lease theo block của v1 đã ngừng dùng; không chặn nhiều thiết bị toàn tài khoản. Quyền mới lấy learning_user_leases.';
COMMENT ON COLUMN learning_hub.block_progress.session_expires_at IS 'Hạn block lease v1 đã ngừng dùng; không fallback khi user lease thiếu/không hợp lệ.';
COMMENT ON COLUMN learning_hub.block_progress.session_fence IS 'Fence block v1 đã ngừng dùng; nguồn chuẩn là learning_user_leases.session_fence.';
COMMENT ON COLUMN learning_hub.block_progress.last_event_sequence IS 'Sequence trong user session gần nhất cập nhật block; không là thứ tự toàn cục qua nhiều session.';

-- 05. Một phiên được ghi tiến độ theo user trên mọi course/block (P05).
-- Release/expiry giữ dòng và fence tăng dần; không DELETE khi logout/takeover.
CREATE TABLE IF NOT EXISTS learning_hub.learning_user_leases (
    user_id varchar(20) PRIMARY KEY,
    session_id uuid,
    lease_state varchar(12) NOT NULL DEFAULT 'released',
    enrollment_id bigint,
    block_id bigint,
    media_asset_id bigint,
    session_fence bigint NOT NULL DEFAULT 0 CHECK (session_fence >= 0),
    expires_at timestamptz,
    last_heartbeat_at timestamptz,
    last_event_sequence bigint NOT NULL DEFAULT 0 CHECK (last_event_sequence >= 0),
    last_event_hash varchar(64),
    last_event_kind varchar(24),
    last_event_accepted_at timestamptz,
    created_by varchar(20) NOT NULL,
    updated_by varchar(20) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    create_time timestamptz NOT NULL DEFAULT now(),
    update_time timestamptz NOT NULL DEFAULT now()
);
-- Session state, event/hash và bộ field bắt buộc theo trạng thái do service kiểm trong transaction.
-- PK user_id, UNIQUE session_id và kiểm fence bằng row lock/CAS vẫn cần để chống ghi đồng thời.
ALTER TABLE learning_hub.learning_user_leases
    DROP CONSTRAINT IF EXISTS learning_user_leases_lease_state_check,
    DROP CONSTRAINT IF EXISTS learning_user_leases_check,
    DROP CONSTRAINT IF EXISTS learning_user_leases_check1;
CREATE UNIQUE INDEX IF NOT EXISTS ux_learning_user_leases_session
    ON learning_hub.learning_user_leases(session_id) WHERE session_id IS NOT NULL;
COMMENT ON TABLE learning_hub.learning_user_leases IS 'Một dòng learning lease chuẩn cho mỗi tài khoản. BE claim/chuyển nguyên tử; dòng hết hạn không còn quyền ghi.';
COMMENT ON COLUMN learning_hub.learning_user_leases.user_id IS 'Tham chiếu logic users.user_id, giữ số 0 đầu. PK giới hạn một dòng lease/tài khoản, không FK.';
COMMENT ON COLUMN learning_hub.learning_user_leases.session_id IS 'UUID phiên player do server cấp; giữ sau release để ACK CLOSE trùng tới lượt claim tiếp.';
COMMENT ON COLUMN learning_hub.learning_user_leases.lease_state IS 'active chỉ được ghi khi expires_at > giờ server; released không được ghi. Không dùng now() trong predicate index.';
COMMENT ON COLUMN learning_hub.learning_user_leases.enrollment_id IS 'Enrollment của player hiện tại/gần nhất; luôn kiểm owner theo caller đã xác thực.';
COMMENT ON COLUMN learning_hub.learning_user_leases.block_id IS 'Block hiện tại/gần nhất; đổi block cần checkpoint và handoff có thứ tự.';
COMMENT ON COLUMN learning_hub.learning_user_leases.media_asset_id IS 'File bất biến đang chọn; NULL cho block không có media chính.';
COMMENT ON COLUMN learning_hub.learning_user_leases.session_fence IS 'Fence tăng theo user sau mỗi claim/takeover/handoff; không reset khi release dòng.';
COMMENT ON COLUMN learning_hub.learning_user_leases.expires_at IS 'Hạn lease theo giờ server; chỉ gia hạn qua event hợp lệ đã kiểm quyền.';
COMMENT ON COLUMN learning_hub.learning_user_leases.last_heartbeat_at IS 'Mốc sống của session hiện hành được chấp nhận; retry trùng không gia hạn lease.';
COMMENT ON COLUMN learning_hub.learning_user_leases.last_event_sequence IS 'Sequence liên tiếp trong user session; chỉ reset về 0 khi tạo session có fence mới.';
COMMENT ON COLUMN learning_hub.learning_user_leases.last_event_hash IS 'SHA-256 của payload chuẩn hóa và resource IDs để nhận diện retry; không lưu JWT hoặc password.';
COMMENT ON COLUMN learning_hub.learning_user_leases.last_event_kind IS 'Loại event cuối được chấp nhận, gồm CLOSE; retry không được áp tác dụng phụ lần hai.';
COMMENT ON COLUMN learning_hub.learning_user_leases.last_event_accepted_at IS 'Mốc server chấp nhận event mới gần nhất; không tin timestamp từ client.';
COMMENT ON COLUMN learning_hub.learning_user_leases.created_by IS 'Actor đã xác thực tạo dòng lease theo user khi cần.';
COMMENT ON COLUMN learning_hub.learning_user_leases.updated_by IS 'Actor đã xác thực cập nhật lease.';
COMMENT ON COLUMN learning_hub.learning_user_leases.row_version IS 'Optimistic version cùng kiểm session/fence tường minh trong cùng DB transaction.';
COMMENT ON COLUMN learning_hub.learning_user_leases.create_time IS 'Thời điểm DB tạo dòng.';
COMMENT ON COLUMN learning_hub.learning_user_leases.update_time IS 'Thời điểm cập nhật do backend quản lý.';

-- 06. Bảng khoảng xem hiện có, tính duy nhất nghiệp vụ và cấu hình hoàn thành.
ALTER TABLE learning_hub.video_watch_ranges
    DROP CONSTRAINT IF EXISTS ck_video_ranges_start,
    ADD CONSTRAINT ck_video_ranges_start CHECK (start_second >= 0),
    DROP CONSTRAINT IF EXISTS ck_video_ranges_valid,
    ADD CONSTRAINT ck_video_ranges_valid CHECK (end_second > start_second);
CREATE INDEX IF NOT EXISTS ix_watch_ranges_end ON learning_hub.video_watch_ranges(block_progress_id, end_second);
COMMENT ON TABLE learning_hub.video_watch_ranges IS 'Các khoảng vị trí được chấp nhận đã gộp; không tạo dòng mỗi heartbeat hoặc cộng trùng khi xem lại. Service giới hạn theo duration.';
ALTER TABLE learning_hub.course_completion_rules
    ADD COLUMN IF NOT EXISTS require_all_required_quizzes boolean NOT NULL DEFAULT true;
CREATE UNIQUE INDEX IF NOT EXISTS ux_course_completion_rules_revision ON learning_hub.course_completion_rules(revision_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_lesson_progress_enrollment_lesson ON learning_hub.lesson_progress(enrollment_id, lesson_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_course_enrollment_cycle ON learning_hub.course_enrollments(course_id, user_id, cycle_no);
COMMENT ON COLUMN learning_hub.course_completion_rules.require_all_required_quizzes IS 'Yêu cầu đạt mọi assessment bắt buộc khi Quiz Engine được tích hợp; schema không tự chấm bài.';
COMMENT ON COLUMN learning_hub.course_completion_rules.require_all_required_lessons IS 'Mọi bài bắt buộc phải đáp ứng policy của các block bắt buộc.';
COMMENT ON COLUMN learning_hub.course_enrollments.revision_id IS 'Revision bất biến của cycle; xuất bản draft mới không tự đổi lượt học cũ.';
COMMENT ON COLUMN learning_hub.course_enrollments.cycle_no IS 'Lần học lại; giao lại cùng khóa/revision không tự reset tiến độ.';
COMMENT ON COLUMN learning_hub.course_enrollments.completed_at IS 'Mốc thực sự đủ điều kiện hoàn thành; so từng hạn còn hiệu lực để xác định muộn.';

-- 07. View báo cáo giữ thứ tự/kiểu cột v1; chỉ thêm field mới ở cuối.
-- View không thay RLS hoặc phân quyền; repository vẫn lọc owner/scope đã xác thực.
CREATE OR REPLACE VIEW learning_hub.v_learning_recipient_deadline AS
SELECT ar.recipient_id, ar.assignment_id, ar.enrollment_id, ar.user_id,
       ar.effective_start_at, ar.effective_due_at, ar.requirement,
       ar.status AS recipient_status, e.status AS enrollment_status, e.completed_at,
       CASE
           WHEN ar.status <> 'active' OR a.status <> 'active' OR e.status = 'withdrawn' THEN 'not_active'
           WHEN ar.effective_due_at IS NULL THEN 'no_deadline'
           WHEN e.completed_at IS NOT NULL AND e.completed_at > ar.effective_due_at THEN 'completed_late'
           WHEN e.completed_at IS NOT NULL THEN 'completed_on_time'
           WHEN now() > ar.effective_due_at THEN 'overdue_active'
           ELSE 'on_time_active'
       END AS deadline_status,
       a.status AS assignment_status
FROM learning_hub.assignment_recipients ar
JOIN learning_hub.course_enrollments e ON e.enrollment_id = ar.enrollment_id
JOIN learning_hub.course_assignments a ON a.assignment_id = ar.assignment_id;
COMMENT ON VIEW learning_hub.v_learning_recipient_deadline IS 'Trạng thái hạn lowercase theo từng nghĩa vụ, xét cả assignment; BE bắt buộc lọc caller/scope.';

CREATE OR REPLACE VIEW learning_hub.v_course_learning_progress AS
SELECT e.enrollment_id, e.course_id, e.revision_id, e.user_id, e.cycle_no,
       e.status, e.progress_percent AS content_progress_percent,
       e.last_lesson_id, e.last_block_id, e.last_access_at, e.completed_at,
       (e.status = 'completed') AS is_course_completed,
       e.started_at, e.enrolled_at
FROM learning_hub.course_enrollments e;
COMMENT ON VIEW learning_hub.v_course_learning_progress IS 'Tiến độ nội dung khác điều kiện hoàn thành khóa; không suy điểm đạt từ phần trăm nội dung.';

COMMIT;
