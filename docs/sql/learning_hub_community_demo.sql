-- Dữ liệu Community tổng hợp cho FE; CHỈ dev/test/staging, không chạy trên production.
-- Chạy SAU Identity -> Learning -> media/progress -> Knowledge -> Community -> Community catalog.
-- Dùng bộ Identity demo sẵn có; không tạo/reset tài khoản, không cấp quyền moderator/Identity.
-- Chạy TOÀN BỘ một lần. Chạy lại báo lỗi và rollback, không ghi đè dữ liệu FE đã sửa.
-- Không tạo S3 object giả, asset READY, transcript, index vector, lượt AI hoặc kết quả worker giả.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(7142051963);

DO $demo$
DECLARE
    v_org bigint;
    v_author varchar(20);
    v_reader varchar(20);
    v_peer varchar(20);
    v_actor varchar(20);
    v_guilds bigint[] := '{}';
    v_contents bigint[] := '{}';
    v_revisions bigint[] := '{}';
    v_id bigint;
    v_revision bigint;
    v_tag bigint;
    v_collection bigint;
    v_thread bigint;
    v_comment bigint;
    v_parent bigint;
    v_uuid uuid;
    v_now timestamptz := transaction_timestamp();
    v_at timestamptz;
    v_context jsonb;
    v_state text;
    v_visibility text;
    v_row record;
    v_user varchar(20);
    v_i integer;
BEGIN
    -- 1. Kiểm tra dữ liệu gốc, danh mục và marker trước mọi INSERT.
    SELECT org_id INTO STRICT v_org FROM learning_hub.orgs
    WHERE code = 'BIDV110B803' AND status = 'active';
    SELECT user_id INTO STRICT v_author FROM learning_hub.users
    WHERE user_name = 'giangvien' AND org_id = v_org AND status = 'active' AND NOT must_change_password;
    SELECT user_id INTO STRICT v_reader FROM learning_hub.users
    WHERE user_name = 'hocvien' AND org_id = v_org AND status = 'active' AND NOT must_change_password;
    SELECT user_id INTO STRICT v_peer FROM learning_hub.users
    WHERE user_name = 'hocvien.tichhop' AND org_id = v_org AND status = 'active' AND NOT must_change_password;
    IF v_author = v_reader OR v_author = v_peer OR v_reader = v_peer THEN
        RAISE EXCEPTION 'Bộ demo cần ba người dùng khác nhau trong cùng đơn vị.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM learning_hub.audit_log WHERE action = 'COMMUNITY_DEMO_INITIALIZED'
          AND details ->> 'marker' = 'community-fe-demo-v1' AND details ->> 'ownerOrgId' = v_org::text
    ) OR EXISTS (
        SELECT 1 FROM learning_hub.community_guilds WHERE owner_org_id = v_org AND slug LIKE 'fe-demo-%'
    ) THEN
        RAISE EXCEPTION 'Community demo đã có. Không chạy lại hoặc ghi đè dữ liệu đang test.';
    END IF;
    FOR v_row IN SELECT slug FROM (VALUES ('spring-boot'), ('angular'), ('openshift'),
        ('oracle'), ('api-design'), ('testing')) AS s(slug) LOOP
        SELECT tag_id INTO STRICT v_tag FROM learning_hub.community_tags
        WHERE owner_org_id = v_org AND slug = v_row.slug AND status = 'active' AND merged_into_tag_id IS NULL;
    END LOOP;

    -- 2. Ba Guild và membership hợp lệ. Private Guild chỉ active member được đọc nội dung.
    FOR v_row IN SELECT * FROM (VALUES
        (1, 'fe-demo-backend', 'FE-DEMO Backend Chapter', 'organization', 'open'),
        (2, 'fe-demo-frontend', 'FE-DEMO Frontend Chapter', 'organization', 'approval'),
        (3, 'fe-demo-devops', 'FE-DEMO DevOps Guild', 'private', 'invite')
    ) AS s(n, slug, name, visibility, join_policy) ORDER BY n LOOP
        v_uuid := gen_random_uuid();
        INSERT INTO learning_hub.community_guilds
            (owner_org_id, slug, name, description, owner_user_id, visibility, join_policy, status,
             client_request_id, request_hash, created_by, updated_by)
        VALUES (v_org, v_row.slug, v_row.name, 'Hội nhóm tổng hợp để kiểm thử giao diện.', v_author,
            v_row.visibility, v_row.join_policy, 'active', v_uuid,
            encode(sha256(convert_to('community-demo:guild:' || v_uuid, 'UTF8')), 'hex'), v_author, v_author)
        RETURNING guild_id INTO v_id;
        v_guilds[v_row.n] := v_id;
        FOREACH v_user IN ARRAY ARRAY[v_author, v_reader, v_peer] LOOP
            v_state := CASE WHEN v_row.n = 2 AND v_user = v_reader THEN 'pending'
                WHEN v_row.n = 3 AND v_user = v_reader THEN 'invited' ELSE 'active' END;
            INSERT INTO learning_hub.community_guild_members
                (guild_id, user_id, member_role, status, joined_at, approved_by,
                 invited_by, invited_at, invitation_expires_at, created_by, updated_by)
            VALUES (v_id, v_user, CASE WHEN v_user = v_author THEN 'owner' ELSE 'member' END, v_state,
                CASE WHEN v_state = 'active' THEN v_now - interval '7 days' END,
                CASE WHEN v_state = 'active' THEN v_author END,
                CASE WHEN v_state = 'invited' THEN v_author END,
                CASE WHEN v_state = 'invited' THEN v_now END,
                CASE WHEN v_state = 'invited' THEN v_now + interval '7 days' END, v_author, v_author);
        END LOOP;
    END LOOP;

    -- 3. Nội dung đủ chín kind; Wiki/Radar và recording giữ draft vì chưa duyệt/chưa có video thật.
    FOR v_row IN SELECT * FROM (VALUES
        (1, 'question', 'FE-DEMO Oracle UCP: hết kết nối',
            'Trong môi trường tổng hợp, pool UCP báo timeout khi nhiều request cùng chạy. Cần kiểm tra tham số nào?',
            'organization', 'published'),
        (2, 'question', 'FE-DEMO JHipster: xung đột OpenAPI',
            'Sau khi cập nhật JDL, contract OpenAPI và kiểu dữ liệu frontend chưa khớp. Nên đối chiếu theo bước nào?',
            'organization', 'published'),
        (3, 'question', 'FE-DEMO Angular: xử lý lỗi HTTP 200',
            'API trả HTTP 200 nhưng envelope có status ERROR. Frontend nên hiển thị thông báo và validation ra sao?',
            'organization', 'published'),
        (4, 'answer', 'FE-DEMO Kiểm tra giới hạn UCP',
            'Đối chiếu timeout, max pool size và số connection đang dùng. Kiểm tra transaction có đóng connection đúng lúc.',
            'organization', 'published'),
        (5, 'answer', 'FE-DEMO Kiểm tra envelope trước data',
            'Đọc status và messageCode trước khi sử dụng data. Với lỗi validation, ánh xạ errors về trường tương ứng.',
            'organization', 'published'),
        (6, 'article', 'FE-DEMO Checklist thiết kế API',
            E'## Hợp đồng API\n\nThống nhất request, response, version và phân quyền trước khi viết giao diện.\n\n```java\n// Ví dụ minh họa, không phải mã chạy trên server.\n```',
            'organization', 'published'),
        (7, 'article', 'FE-DEMO Ghi chú DevOps trong Guild',
            'Bài tổng hợp dành cho thành viên active của DevOps Guild. Dùng để kiểm tra quyền đọc sau lời mời.',
            'guild', 'published'),
        (8, 'article', 'FE-DEMO Tài liệu chia sẻ có giới hạn',
            'Nội dung tổng hợp chỉ dành cho tác giả và người được cấp quyền đọc. Không chứa dữ liệu nghiệp vụ thật.',
            'restricted', 'published'),
        (9, 'wiki', 'FE-DEMO Wiki: quy ước Java (nháp)',
            'Bản đề xuất quy ước Java của Backend Chapter. Cần submit và moderator độc lập duyệt trước khi xuất bản.',
            'organization', 'draft'),
        (10, 'wiki', 'FE-DEMO Wiki: ví dụ JDL (nháp)',
            'Trang con thuộc quy ước Java. Dùng kiểm tra parent page và sort order; chưa được duyệt xuất bản.',
            'organization', 'draft'),
        (11, 'radar', 'FE-DEMO Tech Radar: Spring Boot (nháp)',
            'Đề xuất đánh giá Spring Boot trong phạm vi nhóm thử nghiệm. Không phải danh sách công nghệ được BIDV phê duyệt.',
            'organization', 'draft'),
        (12, 'architecture_review', 'FE-DEMO Review thiết kế dữ liệu đào tạo',
            E'## Thiết kế dữ liệu\n\nĐề xuất dùng ID scalar và kiểm tra quan hệ trong service. Mời phản biện cách kiểm soát ghi đồng thời.',
            'organization', 'published'),
        (13, 'lesson_learned', 'FE-DEMO Bài học sau lỗi timeout',
            'Tình huống giả lập giúp chia sẻ cách phát hiện, xử lý và ngăn lặp lại lỗi. Không mô tả sự cố production thật.',
            'organization', 'published'),
        (14, 'knowledge_request', 'FE-DEMO Cần hướng dẫn OpenShift cho người mới',
            'Cần tài liệu tổng hợp về triển khai ứng dụng demo lên OpenShift, kèm checklist kiểm tra cấu hình.',
            'organization', 'published'),
        (15, 'recording', 'FE-DEMO Brown Bag Session (nháp chờ video)',
            'Chưa gắn video thật. Hãy upload qua API, chờ asset ready rồi gắn primary_video trước khi publish.',
            'organization', 'draft')
    ) AS s(n, kind, title, body, visibility, state) ORDER BY n LOOP
        v_actor := CASE v_row.n WHEN 4 THEN v_reader WHEN 5 THEN v_peer ELSE v_author END;
        v_uuid := gen_random_uuid();
        v_at := v_now - interval '2 days' + make_interval(mins => v_row.n);
        v_context := CASE v_row.kind
            WHEN 'wiki' THEN '{"wiki":{"documentClass":"guide"}}'::jsonb
            WHEN 'radar' THEN '{"radar":{"technologyName":"Spring Boot","technologyVersion":"3.x",
                "stage":"assess","approvalScope":"Nhóm demo thử nghiệm, chưa phải chuẩn áp dụng."}}'::jsonb
            WHEN 'architecture_review' THEN '{"review":{"problem":"Cần kiểm soát quan hệ scalar và ghi đồng thời khi không dùng FK.",
                "constraints":"Giữ modular monolith và PostgreSQL hiện có.",
                "decisionsNeeded":["Chọn cách kiểm tra quan hệ và khóa ghi phù hợp."]}}'::jsonb
            WHEN 'lesson_learned' THEN jsonb_build_object('lessonLearned', jsonb_build_object(
                'occurredOn', (v_now - interval '3 days')::date,
                'symptoms', 'Request trong môi trường giả lập chờ quá thời gian cấu hình.',
                'rootCause', 'Transaction giữ connection lâu hơn thời gian cần thiết trong tình huống giả lập.',
                'resolution', 'Rút ngắn transaction và trả connection ngay sau thao tác cơ sở dữ liệu.',
                'prevention', 'Thêm kiểm thử tải và theo dõi số connection đang dùng trong môi trường test.'))
            WHEN 'recording' THEN jsonb_build_object('recording', jsonb_build_object(
                'heldAt', v_now - interval '1 day', 'speakers', jsonb_build_array(
                    jsonb_build_object('displayName', 'Diễn giả demo', 'userId', v_author))))
            ELSE '{}'::jsonb END;
        INSERT INTO learning_hub.community_contents
            (kind, owner_org_id, author_user_id, maintainer_user_id, visibility, primary_guild_id,
             lifecycle_status, last_activity_at, client_request_id, request_hash, created_by, updated_by, create_time)
        VALUES (v_row.kind, v_org, v_actor, v_actor, v_row.visibility,
            CASE WHEN v_row.visibility = 'guild' THEN v_guilds[3] END, 'active', v_at, v_uuid,
            encode(sha256(convert_to('community-demo:content:' || v_uuid, 'UTF8')), 'hex'), v_actor, v_actor, v_at)
        RETURNING content_id INTO v_id;
        INSERT INTO learning_hub.community_content_revisions
            (content_id, revision_no, title, body_markdown, summary, context_json, revision_status,
             published_at, published_by, created_by, updated_by, create_time)
        VALUES (v_id, 1, v_row.title, v_row.body, 'Dữ liệu tổng hợp cho FE; không dùng làm tài liệu chính thức.',
            v_context, v_row.state, CASE WHEN v_row.state = 'published' THEN v_at END,
            CASE WHEN v_row.state = 'published' THEN v_actor END, v_actor, v_actor, v_at)
        RETURNING revision_id INTO v_revision;
        UPDATE learning_hub.community_contents SET
            current_published_revision_id = CASE WHEN v_row.state = 'published' THEN v_revision END,
            current_draft_revision_id = CASE WHEN v_row.state = 'draft' THEN v_revision END
        WHERE content_id = v_id;
        v_contents[v_row.n] := v_id;
        v_revisions[v_row.n] := v_revision;
        IF v_row.kind <> 'answer' THEN
            SELECT tag_id INTO STRICT v_tag FROM learning_hub.community_tags WHERE owner_org_id = v_org
                AND slug = CASE WHEN v_row.n IN (3, 5) THEN 'angular' WHEN v_row.n IN (7, 14, 15) THEN 'openshift'
                    WHEN v_row.n = 1 THEN 'oracle' WHEN v_row.n IN (6, 12) THEN 'api-design' ELSE 'spring-boot' END;
            INSERT INTO learning_hub.community_content_tags (content_id, tag_id, created_by, updated_by)
            VALUES (v_id, v_tag, v_actor, v_actor);
        END IF;
        IF v_row.state = 'published' THEN
            -- Event thật ở trạng thái pending; worker sẽ tự fanout/index/ledger khi được bật có chủ đích.
            INSERT INTO learning_hub.community_outbox_events
                (owner_org_id, event_type, actor_user_id, content_id, payload_json, dedup_key,
                 event_status, available_at, attempt_count, created_by, updated_by)
            VALUES (v_org, 'content_published', v_actor, v_id, jsonb_build_object('revisionId', v_revision),
                'community-demo:published:' || v_uuid, 'pending', v_now, 0, v_actor, v_actor);
        END IF;
    END LOOP;

    -- 4. Subtype và accept gắn ĐÚNG revision; answer không có ACL/tag độc lập trên giao diện.
    FOR v_i IN 1..3 LOOP
        INSERT INTO learning_hub.community_questions
            (content_id, resolution_status, created_by, updated_by)
        VALUES (v_contents[v_i], CASE v_i WHEN 1 THEN 'solved' WHEN 2 THEN 'unanswered' ELSE 'answered' END,
            v_author, v_author);
    END LOOP;
    INSERT INTO learning_hub.community_answers (content_id, question_content_id, created_by, updated_by)
    VALUES (v_contents[4], v_contents[1], v_reader, v_reader), (v_contents[5], v_contents[3], v_peer, v_peer);
    UPDATE learning_hub.community_questions SET accepted_answer_content_id = v_contents[4],
        accepted_answer_revision_id = v_revisions[4], accepted_by = v_author,
        accepted_at = v_now - interval '1 day' WHERE content_id = v_contents[1];
    UPDATE learning_hub.community_contents SET last_activity_at = v_now - interval '1 day'
    WHERE content_id = v_contents[1];
    INSERT INTO learning_hub.community_outbox_events
        (owner_org_id, event_type, actor_user_id, content_id, payload_json, dedup_key,
         event_status, available_at, attempt_count, created_by, updated_by)
    VALUES (v_org, 'answer_accept_changed', v_author, v_contents[1],
        jsonb_build_object('recipientUserIds', jsonb_build_array(v_reader)),
        'community-demo:accept:' || v_contents[1], 'pending', v_now, 0, v_author, v_author);
    INSERT INTO learning_hub.community_content_access_grants
        (content_id, user_id, permission, status, created_by, updated_by)
    VALUES (v_contents[8], v_reader, 'read', 'active', v_author, v_author);
    FOR v_i IN 9..10 LOOP
        INSERT INTO learning_hub.community_wiki_pages
            (content_id, guild_id, parent_page_content_id, slug, sort_no, review_due_at, created_by, updated_by)
        VALUES (v_contents[v_i], v_guilds[1], CASE WHEN v_i = 10 THEN v_contents[9] END,
            CASE WHEN v_i = 9 THEN 'fe-demo-java-guide' ELSE 'fe-demo-jdl-template' END,
            v_i - 9, current_date + 30, v_author, v_author);
    END LOOP;
    INSERT INTO learning_hub.community_radar_entries
        (content_id, guild_id, review_due_at, created_by, updated_by)
    VALUES (v_contents[11], v_guilds[1], current_date + 30, v_author, v_author);
    INSERT INTO learning_hub.community_review_proposals
        (content_id, review_status, due_at, created_by, updated_by)
    VALUES (v_contents[12], 'open', v_now + interval '14 days', v_author, v_author);
    INSERT INTO learning_hub.community_review_invitations
        (proposal_content_id, reviewer_user_id, invited_by, status, responded_at, expires_at, created_by, updated_by)
    VALUES (v_contents[12], v_reader, v_author, 'accepted', v_now, v_now + interval '14 days', v_author, v_reader);
    INSERT INTO learning_hub.community_knowledge_requests
        (content_id, request_status, due_at, created_by, updated_by)
    VALUES (v_contents[14], 'open', v_now + interval '14 days', v_author, v_author);
    INSERT INTO learning_hub.community_content_guilds
        (content_id, guild_id, is_pinned, created_by, updated_by)
    VALUES (v_contents[6], v_guilds[1], true, v_author, v_author),
        (v_contents[12], v_guilds[1], false, v_author, v_author);

    -- 5. Một bài published có draft mới để FE thử version/update mà không sửa bản published.
    INSERT INTO learning_hub.community_content_revisions
        (content_id, revision_no, title, body_markdown, summary, revision_status, change_note, created_by, updated_by)
    SELECT content_id, 2, title, body_markdown || E'\n\nBổ sung nháp: kiểm thử API trước khi triển khai.', summary,
        'draft', 'Nháp mở rộng checklist cho FE.', v_author, v_author
    FROM learning_hub.community_content_revisions WHERE revision_id = v_revisions[6]
    RETURNING revision_id INTO v_revision;
    UPDATE learning_hub.community_contents SET current_draft_revision_id = v_revision WHERE content_id = v_contents[6];

    -- 6. Comment/reply và review thread có root comment, giữ đúng proposal/revision.
    FOR v_i IN 1..2 LOOP
        v_uuid := gen_random_uuid();
        INSERT INTO learning_hub.community_comments
            (content_id, author_user_id, parent_comment_id, revision_id, body_markdown, status,
             client_request_id, request_hash, created_by, updated_by)
        VALUES (v_contents[6], CASE WHEN v_i = 1 THEN v_reader ELSE v_author END,
            CASE WHEN v_i = 2 THEN v_parent END, v_revisions[6],
            CASE WHEN v_i = 1 THEN 'Có thể bổ sung ví dụ xử lý lỗi validation cho frontend không?'
                ELSE 'Đã bổ sung trong bản nháp mới; sẽ kiểm tra trước khi xuất bản.' END, 'visible', v_uuid,
            encode(sha256(convert_to('community-demo:comment:' || v_uuid, 'UTF8')), 'hex'),
            CASE WHEN v_i = 1 THEN v_reader ELSE v_author END, CASE WHEN v_i = 1 THEN v_reader ELSE v_author END)
        RETURNING comment_id INTO v_comment;
        IF v_i = 1 THEN v_parent := v_comment; END IF;
    END LOOP;
    v_uuid := gen_random_uuid();
    INSERT INTO learning_hub.community_review_threads
        (proposal_content_id, revision_id, anchor_json, status, client_request_id, request_hash, created_by, updated_by)
    VALUES (v_contents[12], v_revisions[12], '{"type":"section","section":"Thiết kế dữ liệu"}', 'open', v_uuid,
        encode(sha256(convert_to('community-demo:thread:' || v_uuid, 'UTF8')), 'hex'), v_reader, v_reader)
    RETURNING thread_id INTO v_thread;
    INSERT INTO learning_hub.community_comments
        (content_id, author_user_id, revision_id, review_thread_id, body_markdown, status,
         client_request_id, request_hash, created_by, updated_by)
    VALUES (v_contents[12], v_reader, v_revisions[12], v_thread,
        'Cần mô tả khóa ghi để không tạo quan hệ trùng khi hai request chạy cùng lúc.', 'visible', v_uuid,
        encode(sha256(convert_to('community-demo:thread-comment:' || v_uuid, 'UTF8')), 'hex'), v_reader, v_reader);
    UPDATE learning_hub.community_contents SET last_activity_at = v_now
    WHERE content_id IN (v_contents[6], v_contents[12]);

    -- 7. Vote không tự vote; event tương ứng giúp reconcile có nguồn hợp lệ.
    INSERT INTO learning_hub.community_votes (content_id, user_id, value, created_by, updated_by)
    VALUES (v_contents[6], v_reader, 1, v_reader, v_reader), (v_contents[6], v_peer, 1, v_peer, v_peer),
        (v_contents[4], v_author, 1, v_author, v_author);
    FOR v_row IN SELECT * FROM (VALUES (v_contents[6], v_reader), (v_contents[6], v_peer),
        (v_contents[4], v_author)) AS s(content_id, user_id) LOOP
        INSERT INTO learning_hub.community_outbox_events
            (owner_org_id, event_type, actor_user_id, content_id, dedup_key,
             event_status, available_at, attempt_count, created_by, updated_by)
        VALUES (v_org, 'vote_changed', v_row.user_id, v_row.content_id,
            'community-demo:vote:' || v_row.content_id || ':' || v_row.user_id,
            'pending', v_now, 0, v_row.user_id, v_row.user_id);
    END LOOP;
    INSERT INTO learning_hub.community_content_links
        (source_content_id, target_content_id, relation_type, created_by, updated_by)
    VALUES (v_contents[6], v_contents[1], 'related', v_author, v_author),
        (v_contents[13], v_contents[6], 'related', v_author, v_author);

    -- 8. Preferences/expertise/subscriptions không ghi đè cấu hình cá nhân sẵn có.
    FOREACH v_user IN ARRAY ARRAY[v_author, v_reader, v_peer] LOOP
        INSERT INTO learning_hub.community_user_preferences
            (user_id, created_by, updated_by)
        SELECT v_user, v_user, v_user WHERE NOT EXISTS (
            SELECT 1 FROM learning_hub.community_user_preferences WHERE user_id = v_user);
    END LOOP;
    SELECT tag_id INTO STRICT v_tag FROM learning_hub.community_tags
    WHERE owner_org_id = v_org AND slug = 'spring-boot';
    FOREACH v_user IN ARRAY ARRAY[v_reader, v_peer] LOOP
        INSERT INTO learning_hub.community_user_expertise
            (user_id, tag_id, description, available_for_help, created_by, updated_by)
        SELECT v_user, v_tag, 'Chuyên môn tổng hợp cho FE.', true, v_user, v_user WHERE NOT EXISTS (
            SELECT 1 FROM learning_hub.community_user_expertise WHERE user_id = v_user AND tag_id = v_tag);
    END LOOP;
    INSERT INTO learning_hub.community_subscriptions
        (user_id, content_id, delivery_mode, created_by, updated_by)
    VALUES (v_reader, v_contents[2], 'instant', v_reader, v_reader);
    INSERT INTO learning_hub.community_help_requests
        (question_content_id, requested_by, target_user_id, status, due_at, created_by, updated_by)
    SELECT v_contents[2], v_author, v_peer, 'pending', v_now + interval '7 days', v_author, v_author
    WHERE EXISTS (SELECT 1 FROM learning_hub.community_user_expertise
        WHERE user_id = v_peer AND available_for_help);

    -- 9. Bộ sưu tập chia sẻ vẫn lọc quyền từng bài; người nhận không được thấy bài restricted số 8.
    FOR v_i IN 1..2 LOOP
        v_uuid := gen_random_uuid();
        INSERT INTO learning_hub.community_collections
            (owner_user_id, owner_org_id, name, description, visibility, status,
             client_request_id, request_hash, created_by, updated_by)
        VALUES (v_reader, v_org, CASE WHEN v_i = 1 THEN 'FE-DEMO Lộ trình backend' ELSE 'FE-DEMO Bộ sưu tập chia sẻ' END,
            'Tài nguyên tổng hợp để kiểm tra collection và quyền từng mục.',
            CASE WHEN v_i = 1 THEN 'private' ELSE 'restricted' END, 'active', v_uuid,
            encode(sha256(convert_to('community-demo:collection:' || v_uuid, 'UTF8')), 'hex'), v_reader, v_reader)
        RETURNING collection_id INTO v_collection;
        INSERT INTO learning_hub.community_collection_items
            (collection_id, content_id, note, sort_no, created_by, updated_by)
        VALUES (v_collection, v_contents[6], 'Đọc checklist trước khi viết API.', 0, v_reader, v_reader),
            (v_collection, v_contents[8], 'Ghi chú riêng về tài liệu có giới hạn.', 1, v_reader, v_reader);
        IF v_i = 2 THEN
            INSERT INTO learning_hub.community_collection_access_grants
                (collection_id, user_id, permission, status, created_by, updated_by)
            VALUES (v_collection, v_peer, 'read', 'active', v_reader, v_reader);
        END IF;
    END LOOP;
    INSERT INTO learning_hub.community_reports
        (reporter_user_id, content_id, reason_code, description, report_status, created_by, updated_by)
    VALUES (v_reader, v_contents[6], 'outdated', 'FE-DEMO: cần bổ sung ví dụ mới cho checklist.', 'open', v_reader, v_reader);

    -- Marker/audit cùng transaction: chặn chạy lại kể cả khi FE đã đổi tiêu đề/slug demo.
    INSERT INTO learning_hub.audit_log (actor_id, action, entity_type, entity_id, details)
    VALUES (v_author, 'COMMUNITY_DEMO_INITIALIZED', 'community_demo', v_org::text,
        jsonb_build_object('marker', 'community-fe-demo-v1', 'ownerOrgId', v_org,
            'script', 'learning_hub_community_demo.sql', 'contentCount', cardinality(v_contents)));
    RAISE NOTICE 'Community demo: % content, % Guild; không tạo file/index/AI hoặc grant quản trị.',
        cardinality(v_contents), cardinality(v_guilds);
END;
$demo$;
COMMIT;
