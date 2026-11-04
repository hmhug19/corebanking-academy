-- Dữ liệu Assessment tổng hợp cho FE; PostgreSQL 14+.
-- Chạy thủ công toàn bộ file SAU Identity + Learning + media/progress + Assessment + Learning demo.
-- Chỉ dev/test: không chạy trên production. Không sửa user, password, role, schema hoặc dữ liệu đã có.
-- Dùng ID tự tăng + RETURNING, không giả định ID khóa/bài học và không chỉnh sequence.
-- Chạy một lần: lần hai báo lỗi và rollback, không nhân đôi/ghi đè dữ liệu FE đã sửa.
--
-- Tạo 3 nhóm năng lực (mã FE-DEMO-CMP-*), và cho FE-DEMO-C1, FE-DEMO-C2 (khóa published của Learning demo):
--   * 8 câu hỏi ACTIVE mỗi khóa (SINGLE và MULTIPLE, trải đều 3 nhóm năng lực, gắn với bài học của khóa);
--   * 1 đề PRACTICE (PARTIAL, nhiều lượt) và 1 đề FINAL_EXAM, cả hai PUBLISHED, pool là 8 câu của khóa.
-- FE-DEMO-C2 FINAL_EXAM đặt require_lessons_completed = true: học viên demo chưa học khóa này nên màn giới thiệu
-- trả blockReason LESSONS_REQUIRED. Các đề còn lại bắt đầu được (canStart) với hocvien đã được giao khóa.
-- Không tạo lượt thi, lượt cấp thêm hay chấm lại: các bảng quiz_attempts, attempt_*, quiz_attempt_grants và
-- attempt_regrades giữ trống để thử luồng làm bài thật qua API.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(7142051964);

DO $demo$
DECLARE
    v_actor varchar(20);
    v_now timestamptz := transaction_timestamp();
    v_comp jsonb := '{}'::jsonb;
    v_competency bigint;
    v_course record;
    v_lessons bigint[];
    v_item jsonb;
    v_opt jsonb;
    v_question bigint;
    v_quiz bigint;
    v_questions bigint[];
    v_i integer;
    v_j integer;
    v_data constant jsonb := $json$
    {
      "competencies": [
        {"code": "FE-DEMO-CMP-ARCH", "name": "Kiến trúc và tổng quan hệ thống", "description": "Thành phần, phân hệ và luồng dữ liệu của Core Banking.", "sort": 1},
        {"code": "FE-DEMO-CMP-OPS", "name": "Vận hành và quy trình", "description": "Quy trình vận hành, thay đổi và xử lý sự cố hằng ngày.", "sort": 2},
        {"code": "FE-DEMO-CMP-CTRL", "name": "Kiểm soát và tuân thủ", "description": "Phân quyền, kiểm soát nội bộ và an toàn thông tin.", "sort": 3}
      ],
      "courses": {
        "FE-DEMO-C1": [
          {"c": "ARCH", "t": "SINGLE", "d": "EASY", "p": 1, "q": "Core Banking chịu trách nhiệm chính cho việc nào sau đây?", "e": "Core Banking là hệ thống ghi nhận và xử lý giao dịch, tài khoản và số dư của ngân hàng.", "o": [["Ghi nhận giao dịch, tài khoản và số dư của khách hàng", true], ["Thiết kế giao diện website ngân hàng", false], ["Quản lý email nội bộ của chi nhánh", false], ["Lập lịch họp của ban lãnh đạo", false]]},
          {"c": "ARCH", "t": "MULTIPLE", "d": "MEDIUM", "p": 2, "q": "Những phân hệ nào thường nằm trong Core Banking?", "e": "Tiền gửi và tín dụng là phân hệ nghiệp vụ lõi; sổ cái ghi nhận mọi bút toán.", "o": [["Tiền gửi", true], ["Tín dụng", true], ["Sổ cái", true], ["Trình soạn thảo văn bản", false]]},
          {"c": "ARCH", "t": "SINGLE", "d": "MEDIUM", "p": 1, "q": "Kênh giao dịch (ATM, Internet Banking) kết nối với Core Banking như thế nào?", "e": "Các kênh gọi Core Banking qua lớp tích hợp để Core là nguồn dữ liệu duy nhất.", "o": [["Qua lớp tích hợp, Core Banking là nguồn dữ liệu duy nhất", true], ["Mỗi kênh giữ một bản sao số dư riêng", false], ["Kênh ghi trực tiếp vào bảng của Core", false], ["Không cần kết nối, kênh tự đối soát cuối tháng", false]]},
          {"c": "OPS", "t": "SINGLE", "d": "EASY", "p": 1, "q": "Xử lý cuối ngày (EOD) có mục đích chính là gì?", "e": "EOD chốt dữ liệu ngày, tính lãi, đối soát và chuyển ngày làm việc.", "o": [["Chốt dữ liệu trong ngày và chuyển sang ngày làm việc mới", true], ["Xóa toàn bộ giao dịch đã hoàn tất", false], ["Khởi động lại máy chủ ứng dụng", false], ["Gửi thông báo khuyến mãi cho khách hàng", false]]},
          {"c": "OPS", "t": "MULTIPLE", "d": "HARD", "p": 2, "q": "Trước khi chạy EOD cần xác nhận những việc nào?", "e": "Giao dịch treo và sao lưu phải được kiểm tra trước khi chốt ngày.", "o": [["Không còn giao dịch treo chưa xử lý", true], ["Đã có bản sao lưu gần nhất", true], ["Đã tắt toàn bộ cảnh báo giám sát", false], ["Đã xóa nhật ký của ngày hôm trước", false]]},
          {"c": "OPS", "t": "SINGLE", "d": "MEDIUM", "p": 1, "q": "Khi một job EOD báo lỗi, bước đầu tiên đúng là gì?", "e": "Cần đọc nhật ký để xác định nguyên nhân trước khi chạy lại, tránh ghi trùng bút toán.", "o": [["Đọc nhật ký lỗi và xác định nguyên nhân", true], ["Chạy lại job ngay nhiều lần", false], ["Bỏ qua job và chạy bước kế tiếp", false], ["Xóa dữ liệu của job lỗi", false]]},
          {"c": "CTRL", "t": "SINGLE", "d": "EASY", "p": 1, "q": "Nguyên tắc quyền tối thiểu nghĩa là gì?", "e": "Mỗi người chỉ được cấp quyền đủ để làm công việc của mình.", "o": [["Mỗi người chỉ có quyền cần thiết cho công việc của mình", true], ["Mọi người dùng chung một tài khoản quản trị", false], ["Quyền được cấp vĩnh viễn khi vào làm", false], ["Chỉ quản lý mới được đăng nhập", false]]},
          {"c": "CTRL", "t": "MULTIPLE", "d": "MEDIUM", "p": 2, "q": "Hành vi nào làm tăng rủi ro an toàn thông tin?", "e": "Chia sẻ mật khẩu và để tài khoản không dùng vẫn hoạt động đều là rủi ro.", "o": [["Chia sẻ mật khẩu cho đồng nghiệp", true], ["Giữ tài khoản của người đã nghỉ việc", true], ["Bật xác thực nhiều lớp", false], ["Đổi mật khẩu theo chu kỳ quy định", false]]}
        ],
        "FE-DEMO-C2": [
          {"c": "OPS", "t": "SINGLE", "d": "EASY", "p": 1, "q": "Một thay đổi trên hệ thống Core Banking cần bắt đầu bằng bước nào?", "e": "Mọi thay đổi bắt đầu từ một yêu cầu thay đổi được ghi nhận và đánh giá.", "o": [["Lập yêu cầu thay đổi và đánh giá tác động", true], ["Triển khai thẳng lên môi trường thật", false], ["Báo cho khách hàng trước", false], ["Xóa bản sao lưu cũ", false]]},
          {"c": "OPS", "t": "MULTIPLE", "d": "MEDIUM", "p": 2, "q": "Hồ sơ của một thay đổi cần có những nội dung nào?", "e": "Kế hoạch triển khai và kế hoạch quay lui là bắt buộc để xử lý khi thay đổi thất bại.", "o": [["Kế hoạch triển khai", true], ["Kế hoạch quay lui", true], ["Kết quả kiểm thử", true], ["Danh sách món ăn trưa của nhóm", false]]},
          {"c": "OPS", "t": "SINGLE", "d": "HARD", "p": 1, "q": "Thay đổi khẩn cấp khác thay đổi thường ở điểm nào?", "e": "Thay đổi khẩn cấp đi theo đường duyệt rút gọn nhưng vẫn phải được ghi nhận và rà soát sau.", "o": [["Duyệt rút gọn nhưng vẫn phải ghi nhận và rà soát sau", true], ["Không cần ghi nhận ở đâu cả", false], ["Không cần kế hoạch quay lui", false], ["Chỉ một người tự duyệt và tự triển khai", false]]},
          {"c": "CTRL", "t": "SINGLE", "d": "MEDIUM", "p": 1, "q": "Vì sao người triển khai không nên là người duy nhất duyệt thay đổi?", "e": "Phân tách nhiệm vụ giúp phát hiện sai sót và gian lận.", "o": [["Để phân tách nhiệm vụ và có kiểm soát chéo", true], ["Để tiết kiệm thời gian", false], ["Vì người duyệt luôn rảnh hơn", false], ["Vì quy định chỉ áp dụng cho kiểm toán", false]]},
          {"c": "CTRL", "t": "MULTIPLE", "d": "MEDIUM", "p": 2, "q": "Những bằng chứng nào cần lưu sau khi triển khai thay đổi?", "e": "Phê duyệt và nhật ký triển khai giúp truy vết khi kiểm toán.", "o": [["Bản phê duyệt", true], ["Nhật ký triển khai", true], ["Ảnh chụp màn hình cá nhân không liên quan", false], ["Tin nhắn trò chuyện ngoài công việc", false]]},
          {"c": "CTRL", "t": "SINGLE", "d": "EASY", "p": 1, "q": "Nhật ký kiểm toán (audit log) dùng để làm gì?", "e": "Audit log cho phép truy vết ai đã làm gì và khi nào.", "o": [["Truy vết ai đã làm gì và vào lúc nào", true], ["Tăng tốc độ xử lý giao dịch", false], ["Thay thế bản sao lưu", false], ["Hiển thị quảng cáo cho nhân viên", false]]},
          {"c": "ARCH", "t": "SINGLE", "d": "MEDIUM", "p": 1, "q": "Môi trường kiểm thử (UAT) tồn tại để làm gì?", "e": "UAT cho phép xác nhận thay đổi hoạt động đúng trước khi lên môi trường thật.", "o": [["Xác nhận thay đổi hoạt động đúng trước khi lên môi trường thật", true], ["Lưu trữ dữ liệu khách hàng thật lâu dài", false], ["Thay thế môi trường sản xuất khi quá tải", false], ["Chạy xử lý cuối ngày chính thức", false]]},
          {"c": "ARCH", "t": "MULTIPLE", "d": "HARD", "p": 2, "q": "Thay đổi cấu hình tham số của Core có thể ảnh hưởng đến những phần nào?", "e": "Tham số dùng chung ảnh hưởng nhiều phân hệ và cả các kênh kết nối.", "o": [["Nhiều phân hệ dùng chung tham số đó", true], ["Các kênh giao dịch kết nối vào Core", true], ["Chỉ một màn hình duy nhất", false], ["Không ảnh hưởng gì vì chỉ là cấu hình", false]]}
        ]
      }
    }
    $json$::jsonb;
BEGIN
    -- 1. Resolve Identity/Learning demo; dừng trước INSERT nếu thiếu dữ liệu nền hoặc đã chạy.
    SELECT u.user_id INTO STRICT v_actor FROM learning_hub.users u JOIN learning_hub.orgs o ON o.org_id = u.org_id
    WHERE u.user_name = 'giangvien' AND o.code = 'BIDV110B803' AND u.status = 'active' AND NOT u.must_change_password;
    IF (SELECT count(*) FROM learning_hub.courses c JOIN learning_hub.course_revisions r
            ON r.revision_id = c.published_revision_id
        WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND r.state = 'published') <> 2 THEN
        RAISE EXCEPTION 'Thiếu khóa published FE-DEMO-C1/C2. Chạy learning_hub_learning_demo.sql trước.';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.competencies WHERE code LIKE 'FE-DEMO-%')
       OR EXISTS (SELECT 1 FROM learning_hub.questions WHERE code LIKE 'FE-DEMO-%')
       OR EXISTS (SELECT 1 FROM learning_hub.quizzes q JOIN learning_hub.courses c USING (course_id)
                  WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND NOT q.is_deleted) THEN
        RAISE EXCEPTION 'Đã có dữ liệu Assessment demo. Không chạy lại hoặc ghi đè dữ liệu đã có.';
    END IF;

    -- 2. Nhóm năng lực.
    FOR v_item IN SELECT * FROM jsonb_array_elements(v_data -> 'competencies') LOOP
        INSERT INTO learning_hub.competencies (code, name, description, sort_no, created_by, updated_by)
        VALUES (v_item ->> 'code', v_item ->> 'name', v_item ->> 'description', (v_item ->> 'sort')::integer,
                v_actor, v_actor)
        RETURNING competency_id INTO v_competency;
        v_comp := v_comp || jsonb_build_object(substring(v_item ->> 'code' FROM 'FE-DEMO-CMP-(.*)$'), v_competency);
    END LOOP;

    -- 3. Câu hỏi, phương án và hai đề cho mỗi khóa.
    FOR v_course IN
        SELECT c.course_id, c.code, r.title, r.program_id
        FROM learning_hub.courses c JOIN learning_hub.course_revisions r ON r.revision_id = c.published_revision_id
        WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') ORDER BY c.code
    LOOP
        SELECT array_agg(l.lesson_id ORDER BY ch.sort_no, l.sort_no) INTO v_lessons
        FROM learning_hub.courses c
        JOIN learning_hub.course_chapters ch ON ch.revision_id = c.published_revision_id
        JOIN learning_hub.course_lessons l ON l.chapter_id = ch.chapter_id
        WHERE c.course_id = v_course.course_id;
        IF coalesce(cardinality(v_lessons), 0) = 0 THEN
            RAISE EXCEPTION 'Khóa % chưa có bài học.', v_course.code;
        END IF;

        v_questions := '{}';
        v_i := 0;
        FOR v_item IN SELECT * FROM jsonb_array_elements(v_data -> 'courses' -> v_course.code) LOOP
            v_i := v_i + 1;
            INSERT INTO learning_hub.questions
                (code, program_id, course_id, lesson_id, competency_id, question_type, content, explanation, difficulty, points,
                 created_by, updated_by)
            VALUES ('FE-DEMO-Q' || right(v_course.code, 1) || '-' || lpad(v_i::text, 2, '0'), v_course.program_id, v_course.course_id,
                    v_lessons[1 + (v_i - 1) % cardinality(v_lessons)], (v_comp ->> (v_item ->> 'c'))::bigint,
                    v_item ->> 't', v_item ->> 'q', v_item ->> 'e', v_item ->> 'd', (v_item ->> 'p')::numeric,
                    v_actor, v_actor)
            RETURNING question_id INTO v_question;
            v_questions := array_append(v_questions, v_question);
            v_j := 0;
            FOR v_opt IN SELECT * FROM jsonb_array_elements(v_item -> 'o') LOOP
                v_j := v_j + 1;
                INSERT INTO learning_hub.question_options (question_id, content, is_correct, sort_no)
                VALUES (v_question, v_opt ->> 0, (v_opt ->> 1)::boolean, v_j);
            END LOOP;
        END LOOP;

        -- Luyện tập: nhiều lượt, chấm một phần cho câu nhiều đáp án.
        INSERT INTO learning_hub.quizzes
            (course_id, title, description, quiz_type, status, selection_mode, question_count, time_limit_minutes,
             pass_percent, scoring_mode, score_method, review_policy, max_attempts, retake_wait_hours,
             allow_retake_after_pass, require_lessons_completed, published_at, created_by, updated_by)
        VALUES (v_course.course_id, 'Luyện tập: ' || v_course.title,
                'Bộ câu hỏi luyện tập, làm lại nhiều lần, xem đáp án ngay sau khi nộp.',
                'PRACTICE', 'PUBLISHED', 'RANDOM_POOL', 5, 10, 60, 'PARTIAL', 'BEST', 'AFTER_SUBMIT', 20, 0,
                true, false, v_now, v_actor, v_actor)
        RETURNING quiz_id INTO v_quiz;
        INSERT INTO learning_hub.quiz_questions (quiz_id, question_id, sort_no, created_by)
        SELECT v_quiz, q, ord, v_actor FROM unnest(v_questions) WITH ORDINALITY AS t(q, ord);

        -- Thi cuối khóa: chấm tất cả-hoặc-không, ít lượt, chỉ xem đáp án sau khi đạt.
        INSERT INTO learning_hub.quizzes
            (course_id, title, description, quiz_type, status, selection_mode, question_count, time_limit_minutes,
             pass_percent, scoring_mode, score_method, review_policy, max_attempts, retake_wait_hours,
             allow_retake_after_pass, require_lessons_completed, published_at, created_by, updated_by)
        VALUES (v_course.course_id, 'Thi cuối khóa: ' || v_course.title,
                'Bài thi cuối khóa, chấm tất cả-hoặc-không, tối đa 2 lượt.',
                'FINAL_EXAM', 'PUBLISHED', 'RANDOM_POOL', 6, 15, 70, 'ALL_OR_NOTHING', 'BEST', 'AFTER_PASS', 2, 0,
                false, v_course.code = 'FE-DEMO-C2', v_now, v_actor, v_actor)
        RETURNING quiz_id INTO v_quiz;
        INSERT INTO learning_hub.quiz_questions (quiz_id, question_id, sort_no, created_by)
        SELECT v_quiz, q, ord, v_actor FROM unnest(v_questions) WITH ORDINALITY AS t(q, ord);
    END LOOP;
END;
$demo$;
COMMIT;

-- Mapping ID thực tế cho FE/Postman: không hardcode ID theo thứ tự tạo.
SELECT c.code AS course, q.quiz_id, q.quiz_type, q.status, q.question_count, q.require_lessons_completed,
       (SELECT count(*) FROM learning_hub.quiz_questions qq WHERE qq.quiz_id = q.quiz_id) AS pool_size
FROM learning_hub.quizzes q JOIN learning_hub.courses c USING (course_id)
WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND NOT q.is_deleted
ORDER BY c.code, q.quiz_type;
