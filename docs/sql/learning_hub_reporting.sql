-- PostgreSQL 14+. Dữ liệu tổng hợp phục vụ Dashboard học viên và báo cáo Assessment.
-- Chạy một lần sau Identity -> Learning -> media/progress -> Assessment.
-- Chạy trong cửa sổ bảo trì: tạm dừng ghi/nộp bài/chấm lại trước khi chạy toàn file.
-- Không FK, trigger, job hoặc seed; không sửa kết quả thi gốc. Không chạy tự động khi startup.
-- Backend đồng bộ cùng transaction chấm/nộp/hủy lượt; chạy script trước khi dùng bản backend có Dashboard.
BEGIN;
SET LOCAL lock_timeout = '10s';

-- Giữ dữ liệu nguồn ổn định trong lúc khởi tạo; thiếu quyền hoặc không lấy được khóa thì rollback.
LOCK TABLE learning_hub.quiz_attempts, learning_hub.attempt_questions,
           learning_hub.attempt_answers IN SHARE MODE;

-- 1. Một dòng cho mỗi (bài kiểm tra, học viên) đã có ít nhất một lượt được chấm.
CREATE TABLE learning_hub.quiz_user_results (
    quiz_id bigint NOT NULL,
    user_id varchar(20) NOT NULL,
    graded_attempt_count bigint NOT NULL,
    best_attempt_id bigint NOT NULL,
    last_attempt_id bigint NOT NULL,
    best_score_percent numeric(5,2) NOT NULL,
    last_score_percent numeric(5,2) NOT NULL,
    ever_passed boolean NOT NULL,
    last_passed boolean NOT NULL,
    last_submitted_at timestamptz NOT NULL,
    update_time timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (quiz_id, user_id)
);
COMMENT ON TABLE learning_hub.quiz_user_results IS
    'Read model theo đề/học viên, dựng lại từ quiz_attempts; không là nguồn lịch sử hoặc thay thế kiểm quyền.';
COMMENT ON COLUMN learning_hub.quiz_user_results.quiz_id IS 'ID đề tham chiếu logic; service kiểm tra tồn tại.';
COMMENT ON COLUMN learning_hub.quiz_user_results.user_id IS 'Mã cán bộ dạng chuỗi, giữ số 0 đầu.';
COMMENT ON COLUMN learning_hub.quiz_user_results.graded_attempt_count IS
    'Số lượt SUBMITTED/AUTO_SUBMITTED; không gồm IN_PROGRESS/VOIDED, không dùng tính số lượt còn lại.';
COMMENT ON COLUMN learning_hub.quiz_user_results.best_attempt_id IS
    'Lượt điểm cao nhất; hòa điểm chọn submitted_at DESC, attempt_id DESC.';
COMMENT ON COLUMN learning_hub.quiz_user_results.last_attempt_id IS
    'Lượt được nộp gần nhất theo submitted_at DESC, attempt_id DESC; không xếp theo ngày chấm lại.';
COMMENT ON COLUMN learning_hub.quiz_user_results.best_score_percent IS 'Điểm cao nhất đã chấm, thang 0-100.';
COMMENT ON COLUMN learning_hub.quiz_user_results.last_score_percent IS 'Điểm của last_attempt_id, thang 0-100.';
COMMENT ON COLUMN learning_hub.quiz_user_results.ever_passed IS
    'Từng có lượt hợp lệ đạt theo snapshot của lượt đó; dùng cho BEST như logic Assessment hiện tại.';
COMMENT ON COLUMN learning_hub.quiz_user_results.last_passed IS 'Trạng thái đạt của last_attempt_id; dùng cho LAST.';
COMMENT ON COLUMN learning_hub.quiz_user_results.last_submitted_at IS 'Thời điểm nộp của last_attempt_id.';
COMMENT ON COLUMN learning_hub.quiz_user_results.update_time IS
    'Thời điểm tính lại read model; backend phải cập nhật khi ghi, DB không có trigger tự cập nhật.';

CREATE INDEX ix_rpt_quiz_user_recent
    ON learning_hub.quiz_user_results (user_id, last_submitted_at DESC, quiz_id);

-- 2. Một dòng cho mỗi (lượt đã chấm, nhóm kiến thức có trong snapshot đề).
CREATE TABLE learning_hub.attempt_competency_scores (
    attempt_id bigint NOT NULL,
    competency_id bigint NOT NULL,
    question_count bigint NOT NULL,
    correct_count bigint NOT NULL,
    earned_score numeric(12,2) NOT NULL,
    max_score numeric(12,2) NOT NULL,
    update_time timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, competency_id)
);
COMMENT ON TABLE learning_hub.attempt_competency_scores IS
    'Điểm theo nhóm kiến thức của một lượt; dựng từ snapshot câu/đáp án đã chấm, không đọc ngân hàng hiện tại.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.attempt_id IS
    'ID lượt hợp lệ SUBMITTED/AUTO_SUBMITTED; service xóa read model của lượt nếu chuyển VOIDED.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.competency_id IS
    'Nhóm kiến thức đã chụp trong attempt_questions; service kiểm quan hệ logic, không có FK.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.question_count IS
    'Số câu thuộc nhóm trong snapshot, bao gồm câu bỏ trống.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.correct_count IS
    'Số câu có is_correct=true; không đồng nghĩa điểm đạt tối đa khi chấm PARTIAL.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.earned_score IS
    'Tổng score_awarded; câu không có đáp án lưu tính 0, không suy điểm từ correct_count.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.max_score IS
    'Tổng points trong snapshot; tổng hợp nhiều lượt bằng SUM(earned_score)/SUM(max_score), không AVG phần trăm.';
COMMENT ON COLUMN learning_hub.attempt_competency_scores.update_time IS
    'Thời điểm tính lại nhóm điểm; backend phải cập nhật sau nộp bài/chấm lại.';

CREATE INDEX ix_rpt_competency_attempt
    ON learning_hub.attempt_competency_scores (competency_id, attempt_id);

-- 3. Index cho tiếp tục học và tra lịch sử thi cá nhân; không lặp index đơn vị/thời gian đã có.
CREATE INDEX ix_rpt_enrollment_resume
    ON learning_hub.course_enrollments (user_id, last_access_at DESC, enrollment_id DESC)
    WHERE status <> 'withdrawn' AND last_access_at IS NOT NULL;
CREATE INDEX ix_rpt_attempt_user_submitted
    ON learning_hub.quiz_attempts (user_id, submitted_at DESC, attempt_id DESC)
    WHERE status IN ('SUBMITTED', 'AUTO_SUBMITTED');

-- 4. Khởi tạo từ lịch sử thật. Không tạo dòng 0 giả cho người chưa thi hoặc nhóm chưa được đánh giá.
INSERT INTO learning_hub.attempt_competency_scores
    (attempt_id, competency_id, question_count, correct_count, earned_score, max_score)
SELECT a.attempt_id, q.competency_id, count(*), count(*) FILTER (WHERE ans.is_correct),
       coalesce(sum(ans.score_awarded), 0), sum(q.points)
FROM learning_hub.quiz_attempts a
JOIN learning_hub.attempt_questions q ON q.attempt_id = a.attempt_id
LEFT JOIN learning_hub.attempt_answers ans ON ans.attempt_question_id = q.attempt_question_id
WHERE a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
  AND q.competency_id IS NOT NULL
GROUP BY a.attempt_id, q.competency_id;

WITH ranked AS (
    SELECT a.*,
           row_number() OVER (PARTITION BY quiz_id, user_id
                              ORDER BY score_percent DESC NULLS LAST, submitted_at DESC, attempt_id DESC) AS best_no,
           row_number() OVER (PARTITION BY quiz_id, user_id
                              ORDER BY submitted_at DESC, attempt_id DESC) AS last_no
    FROM learning_hub.quiz_attempts a
    WHERE a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
)
INSERT INTO learning_hub.quiz_user_results
    (quiz_id, user_id, graded_attempt_count, best_attempt_id, last_attempt_id,
     best_score_percent, last_score_percent, ever_passed, last_passed, last_submitted_at)
SELECT quiz_id, user_id, count(*),
       max(attempt_id) FILTER (WHERE best_no = 1), max(attempt_id) FILTER (WHERE last_no = 1),
       max(score_percent) FILTER (WHERE best_no = 1), max(score_percent) FILTER (WHERE last_no = 1),
       bool_or(passed), bool_or(passed) FILTER (WHERE last_no = 1),
       max(submitted_at) FILTER (WHERE last_no = 1)
FROM ranked
GROUP BY quiz_id, user_id;

ANALYZE learning_hub.quiz_user_results;
ANALYZE learning_hub.attempt_competency_scores;
COMMIT;

-- Chỉ kiểm tra số dòng đã khởi tạo; 0 là hợp lệ khi chưa có lượt thi được chấm.
SELECT 'quiz_user_results' AS table_name, count(*) AS row_count FROM learning_hub.quiz_user_results
UNION ALL
SELECT 'attempt_competency_scores', count(*) FROM learning_hub.attempt_competency_scores;
