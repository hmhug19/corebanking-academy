-- Kiểm tra Community sau DDL/seed/migration; chỉ đọc, không sửa hoặc tự dọn dữ liệu.
-- Chạy trên DB được phép, ngoài giờ cao điểm nếu dữ liệu lớn. Timeout 60s; lỗi thì dừng để DBA xem nguyên nhân.
-- Baseline hiện tại: 51 bảng, 670 cột, 142 tham chiếu logic được khai báo trong từ điển dữ liệu.
BEGIN READ ONLY;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;

DO $verify$
DECLARE
    v_ref record;
    v_count bigint;
BEGIN
    -- 1. Đối chiếu baseline và yêu cầu không FK/UNIQUE nghiệp vụ vật lý (PK vẫn có unique index).
    SELECT count(*) INTO v_count FROM information_schema.tables
    WHERE table_schema = 'learning_hub' AND table_type = 'BASE TABLE' AND table_name LIKE 'community\_%' ESCAPE '\';
    IF v_count <> 51 THEN RAISE EXCEPTION 'Cần 51 bảng Community, hiện có %.', v_count; END IF;
    SELECT count(*) INTO v_count FROM information_schema.columns
    WHERE table_schema = 'learning_hub' AND table_name LIKE 'community\_%' ESCAPE '\';
    IF v_count <> 670 THEN RAISE EXCEPTION 'Cần 670 cột Community, hiện có %.', v_count; END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'learning_hub' AND t.relname LIKE 'community\_%' ESCAPE '\'
          AND c.contype IN ('f', 'u')
    ) THEN RAISE EXCEPTION 'Có FK/UNIQUE nghiệp vụ ngoài baseline đã thống nhất.'; END IF;

    -- 2. Scalar ID không được trỏ tới dòng không tồn tại; null chỉ được bỏ qua khi quan hệ tùy chọn.
    FOR v_ref IN SELECT * FROM (VALUES
        ('community_contents', 'owner_org_id', 'orgs', 'org_id'),
        ('community_contents', 'author_user_id', 'users', 'user_id'),
        ('community_contents', 'maintainer_user_id', 'users', 'user_id'),
        ('community_contents', 'primary_guild_id', 'community_guilds', 'guild_id'),
        ('community_contents', 'current_published_revision_id', 'community_content_revisions', 'revision_id'),
        ('community_contents', 'current_draft_revision_id', 'community_content_revisions', 'revision_id'),
        ('community_contents', 'deleted_by', 'users', 'user_id'),
        ('community_content_revisions', 'content_id', 'community_contents', 'content_id'),
        ('community_content_revisions', 'published_by', 'users', 'user_id'),
        ('community_content_revisions', 'deleted_by', 'users', 'user_id'),
        ('community_content_access_grants', 'content_id', 'community_contents', 'content_id'),
        ('community_content_access_grants', 'user_id', 'users', 'user_id'),
        ('community_content_access_grants', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_assets', 'owner_org_id', 'orgs', 'org_id'),
        ('community_assets', 'uploaded_by', 'users', 'user_id'),
        ('community_assets', 'deleted_by', 'users', 'user_id'),
        ('community_asset_upload_parts', 'asset_id', 'community_assets', 'asset_id'),
        ('community_revision_assets', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_revision_assets', 'asset_id', 'community_assets', 'asset_id'),
        ('community_tags', 'owner_org_id', 'orgs', 'org_id'),
        ('community_tags', 'merged_into_tag_id', 'community_tags', 'tag_id'),
        ('community_tag_aliases', 'tag_id', 'community_tags', 'tag_id'),
        ('community_content_tags', 'content_id', 'community_contents', 'content_id'),
        ('community_content_tags', 'tag_id', 'community_tags', 'tag_id'),
        ('community_comments', 'content_id', 'community_contents', 'content_id'),
        ('community_comments', 'author_user_id', 'users', 'user_id'),
        ('community_comments', 'parent_comment_id', 'community_comments', 'comment_id'),
        ('community_comments', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_comments', 'review_thread_id', 'community_review_threads', 'thread_id'),
        ('community_comments', 'deleted_by', 'users', 'user_id'),
        ('community_votes', 'content_id', 'community_contents', 'content_id'),
        ('community_votes', 'user_id', 'users', 'user_id'),
        ('community_content_links', 'source_content_id', 'community_contents', 'content_id'),
        ('community_content_links', 'target_content_id', 'community_contents', 'content_id'),
        ('community_content_links', 'target_course_id', 'courses', 'course_id'),
        ('community_content_links', 'target_knowledge_document_id', 'knowledge_documents', 'document_id'),
        ('community_revision_approvals', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_revision_approvals', 'reviewer_user_id', 'users', 'user_id'),
        ('community_guilds', 'owner_org_id', 'orgs', 'org_id'),
        ('community_guilds', 'owner_user_id', 'users', 'user_id'),
        ('community_guild_members', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_guild_members', 'user_id', 'users', 'user_id'),
        ('community_guild_members', 'approved_by', 'users', 'user_id'),
        ('community_guild_members', 'invited_by', 'users', 'user_id'),
        ('community_content_guilds', 'content_id', 'community_contents', 'content_id'),
        ('community_content_guilds', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_moderators', 'user_id', 'users', 'user_id'),
        ('community_moderators', 'org_id', 'orgs', 'org_id'),
        ('community_moderators', 'granted_by', 'users', 'user_id'),
        ('community_questions', 'content_id', 'community_contents', 'content_id'),
        ('community_questions', 'accepted_answer_content_id', 'community_answers', 'content_id'),
        ('community_questions', 'accepted_answer_revision_id', 'community_content_revisions', 'revision_id'),
        ('community_questions', 'accepted_by', 'users', 'user_id'),
        ('community_answers', 'content_id', 'community_contents', 'content_id'),
        ('community_answers', 'question_content_id', 'community_questions', 'content_id'),
        ('community_question_links', 'source_question_id', 'community_questions', 'content_id'),
        ('community_question_links', 'target_question_id', 'community_questions', 'content_id'),
        ('community_help_requests', 'question_content_id', 'community_questions', 'content_id'),
        ('community_help_requests', 'requested_by', 'users', 'user_id'),
        ('community_help_requests', 'target_user_id', 'users', 'user_id'),
        ('community_help_requests', 'target_guild_id', 'community_guilds', 'guild_id'),
        ('community_help_requests', 'responded_by', 'users', 'user_id'),
        ('community_wiki_pages', 'content_id', 'community_contents', 'content_id'),
        ('community_wiki_pages', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_wiki_pages', 'parent_page_content_id', 'community_wiki_pages', 'content_id'),
        ('community_radar_entries', 'content_id', 'community_contents', 'content_id'),
        ('community_radar_entries', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_review_proposals', 'content_id', 'community_contents', 'content_id'),
        ('community_review_proposals', 'concluded_revision_id', 'community_content_revisions', 'revision_id'),
        ('community_review_proposals', 'concluded_by', 'users', 'user_id'),
        ('community_review_invitations', 'proposal_content_id', 'community_review_proposals', 'content_id'),
        ('community_review_invitations', 'reviewer_user_id', 'users', 'user_id'),
        ('community_review_invitations', 'invited_by', 'users', 'user_id'),
        ('community_review_threads', 'proposal_content_id', 'community_review_proposals', 'content_id'),
        ('community_review_threads', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_review_threads', 'revision_asset_id', 'community_revision_assets', 'revision_asset_id'),
        ('community_review_threads', 'resolved_by', 'users', 'user_id'),
        ('community_user_expertise', 'user_id', 'users', 'user_id'),
        ('community_user_expertise', 'tag_id', 'community_tags', 'tag_id'),
        ('community_subscriptions', 'user_id', 'users', 'user_id'),
        ('community_subscriptions', 'content_id', 'community_contents', 'content_id'),
        ('community_subscriptions', 'tag_id', 'community_tags', 'tag_id'),
        ('community_subscriptions', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_subscriptions', 'author_user_id', 'users', 'user_id'),
        ('community_collections', 'owner_user_id', 'users', 'user_id'),
        ('community_collections', 'owner_org_id', 'orgs', 'org_id'),
        ('community_collections', 'primary_guild_id', 'community_guilds', 'guild_id'),
        ('community_collection_items', 'collection_id', 'community_collections', 'collection_id'),
        ('community_collection_items', 'content_id', 'community_contents', 'content_id'),
        ('community_notifications', 'recipient_user_id', 'users', 'user_id'),
        ('community_notifications', 'event_id', 'community_outbox_events', 'event_id'),
        ('community_notifications', 'content_id', 'community_contents', 'content_id'),
        ('community_outbox_events', 'owner_org_id', 'orgs', 'org_id'),
        ('community_outbox_events', 'actor_user_id', 'users', 'user_id'),
        ('community_outbox_events', 'content_id', 'community_contents', 'content_id'),
        ('community_outbox_events', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_reports', 'reporter_user_id', 'users', 'user_id'),
        ('community_reports', 'content_id', 'community_contents', 'content_id'),
        ('community_reports', 'comment_id', 'community_comments', 'comment_id'),
        ('community_reports', 'assigned_moderator_user_id', 'users', 'user_id'),
        ('community_moderation_actions', 'report_id', 'community_reports', 'report_id'),
        ('community_moderation_actions', 'actor_user_id', 'users', 'user_id'),
        ('community_maintenance_reviews', 'content_id', 'community_contents', 'content_id'),
        ('community_maintenance_reviews', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_maintenance_reviews', 'reviewer_user_id', 'users', 'user_id'),
        ('community_collection_access_grants', 'collection_id', 'community_collections', 'collection_id'),
        ('community_collection_access_grants', 'user_id', 'users', 'user_id'),
        ('community_collection_access_grants', 'guild_id', 'community_guilds', 'guild_id'),
        ('community_knowledge_requests', 'content_id', 'community_contents', 'content_id'),
        ('community_knowledge_requests', 'claimed_by', 'users', 'user_id'),
        ('community_user_preferences', 'user_id', 'users', 'user_id'),
        ('community_notification_digest_batches', 'user_id', 'users', 'user_id'),
        ('community_notification_digest_items', 'batch_id', 'community_notification_digest_batches', 'batch_id'),
        ('community_notification_digest_items', 'notification_id', 'community_notifications', 'notification_id'),
        ('community_contribution_events', 'org_id', 'orgs', 'org_id'),
        ('community_contribution_events', 'user_id', 'users', 'user_id'),
        ('community_contribution_events', 'content_id', 'community_contents', 'content_id'),
        ('community_contribution_events', 'source_event_id', 'community_outbox_events', 'event_id'),
        ('community_contribution_events', 'reverses_event_id', 'community_contribution_events', 'contribution_event_id'),
        ('community_badges', 'owner_org_id', 'orgs', 'org_id'),
        ('community_badge_awards', 'badge_id', 'community_badges', 'badge_id'),
        ('community_badge_awards', 'user_id', 'users', 'user_id'),
        ('community_badge_awards', 'source_event_id', 'community_outbox_events', 'event_id'),
        ('community_learning_links', 'course_revision_id', 'course_revisions', 'revision_id'),
        ('community_learning_links', 'lesson_id', 'course_lessons', 'lesson_id'),
        ('community_learning_links', 'content_revision_id', 'community_content_revisions', 'revision_id'),
        ('community_content_views', 'content_id', 'community_contents', 'content_id'),
        ('community_content_views', 'user_id', 'users', 'user_id'),
        ('community_search_chunks', 'content_id', 'community_contents', 'content_id'),
        ('community_search_chunks', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_search_chunks', 'transcript_id', 'community_media_transcripts', 'transcript_id'),
        ('community_media_transcripts', 'asset_id', 'community_assets', 'asset_id'),
        ('community_ai_turns', 'user_id', 'users', 'user_id'),
        ('community_ai_turn_sources', 'turn_id', 'community_ai_turns', 'turn_id'),
        ('community_ai_turn_sources', 'community_chunk_id', 'community_search_chunks', 'chunk_id'),
        ('community_ai_turn_sources', 'knowledge_chunk_id', 'knowledge_document_chunks', 'chunk_id'),
        ('community_ai_turn_sources', 'course_revision_id', 'course_revisions', 'revision_id'),
        ('community_ai_feedback', 'turn_id', 'community_ai_turns', 'turn_id'),
        ('community_ai_feedback', 'user_id', 'users', 'user_id'),
        ('community_mentions', 'mentioned_user_id', 'users', 'user_id'),
        ('community_mentions', 'revision_id', 'community_content_revisions', 'revision_id'),
        ('community_mentions', 'comment_id', 'community_comments', 'comment_id')
    ) AS r(source_table, source_column, target_table, target_column) LOOP
        EXECUTE format('SELECT count(*) FROM learning_hub.%I s LEFT JOIN learning_hub.%I t ON s.%I=t.%I '
            || 'WHERE s.%I IS NOT NULL AND t.%I IS NULL', v_ref.source_table, v_ref.target_table,
            v_ref.source_column, v_ref.target_column, v_ref.source_column, v_ref.target_column) INTO v_count;
        IF v_count > 0 THEN
            RAISE EXCEPTION 'Tham chiếu lỗi %.% -> %.%: % dòng.', v_ref.source_table, v_ref.source_column,
                v_ref.target_table, v_ref.target_column, v_count;
        END IF;
    END LOOP;

    -- 3. Pointer published/draft phải thuộc đúng root và trạng thái; accept phải là answer của đúng question.
    IF EXISTS (
        SELECT 1 FROM learning_hub.community_contents c
        JOIN learning_hub.community_content_revisions r ON r.revision_id = c.current_published_revision_id
        WHERE r.content_id <> c.content_id OR r.revision_status <> 'published'
    ) OR EXISTS (
        SELECT 1 FROM learning_hub.community_contents c
        JOIN learning_hub.community_content_revisions r ON r.revision_id = c.current_draft_revision_id
        WHERE r.content_id <> c.content_id OR r.revision_status NOT IN ('draft', 'submitted')
    ) THEN RAISE EXCEPTION 'Pointer revision khác root hoặc sai trạng thái.'; END IF;
    IF EXISTS (
        SELECT 1 FROM learning_hub.community_questions q
        LEFT JOIN learning_hub.community_answers a ON a.content_id = q.accepted_answer_content_id
        LEFT JOIN learning_hub.community_content_revisions r ON r.revision_id = q.accepted_answer_revision_id
        WHERE (q.accepted_answer_content_id IS NULL) <> (q.accepted_answer_revision_id IS NULL)
          OR (q.accepted_answer_content_id IS NOT NULL AND
            (a.question_content_id IS DISTINCT FROM q.content_id OR r.content_id IS DISTINCT FROM a.content_id
              OR r.revision_status NOT IN ('published', 'superseded')))
    ) THEN RAISE EXCEPTION 'Accept không khớp question/answer/revision.'; END IF;
    IF EXISTS (
        SELECT 1 FROM learning_hub.community_review_threads t
        JOIN learning_hub.community_content_revisions r ON r.revision_id = t.revision_id
        WHERE r.content_id <> t.proposal_content_id
    ) THEN RAISE EXCEPTION 'Review thread trỏ revision của proposal khác.'; END IF;

    -- 4. Quan hệ XOR/grain không có constraint vật lý nên cần kiểm tra chủ động.
    IF EXISTS (SELECT 1 FROM learning_hub.community_content_access_grants
        WHERE num_nonnulls(user_id, guild_id) <> 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_collection_access_grants
        WHERE num_nonnulls(user_id, guild_id) <> 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_subscriptions
        WHERE num_nonnulls(content_id, tag_id, guild_id, author_user_id) <> 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_content_links
        WHERE num_nonnulls(target_content_id, target_course_id, target_knowledge_document_id) <> 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_help_requests
        WHERE num_nonnulls(target_user_id, target_guild_id) <> 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_mentions
        WHERE num_nonnulls(revision_id, comment_id) <> 1)
    THEN RAISE EXCEPTION 'Quan hệ cần đúng một đối tượng đích bị sai.'; END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.community_tags GROUP BY owner_org_id, slug HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_guilds GROUP BY owner_org_id, slug HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_guild_members GROUP BY guild_id, user_id HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_votes GROUP BY content_id, user_id HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_content_tags GROUP BY content_id, tag_id HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_content_revisions GROUP BY content_id, revision_no HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_collection_items GROUP BY collection_id, content_id HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_notifications GROUP BY event_id, recipient_user_id HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM learning_hub.community_notification_digest_items GROUP BY notification_id HAVING count(*) > 1)
    THEN RAISE EXCEPTION 'Có quan hệ trùng grain; không tự xóa hoặc merge dữ liệu.'; END IF;

    -- Đây là kiểm tra cấu trúc/quan hệ, không thay kiểm thử API ACL, S3, worker hoặc truy vấn vector.
    RAISE NOTICE 'PASS: 51 bảng/670 cột, tham chiếu logic, revision/accept/thread và các grain chính.';
END;
$verify$;

SELECT t.table_name, (xpath('/row/count/text()', query_to_xml(
    format('SELECT count(*) FROM learning_hub.%I', t.table_name), false, true, '')))[1]::text::bigint AS row_count
FROM information_schema.tables t
WHERE t.table_schema = 'learning_hub' AND t.table_type = 'BASE TABLE'
  AND t.table_name LIKE 'community\_%' ESCAPE '\'
ORDER BY t.table_name;
COMMIT;
