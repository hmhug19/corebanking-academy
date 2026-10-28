-- Danh mục khởi tạo Community; PostgreSQL 14+. Chạy SAU learning_hub_community.sql.
-- DBA sửa HAI giá trị v_org_code/v_actor_name cho môi trường đích trước khi chạy toàn bộ file.
-- Không tạo tài khoản, đổi mật khẩu, cấp moderator hoặc sửa role Identity.
-- Chạy lại chỉ bổ sung mục còn thiếu; không ghi đè mục đã được chỉnh sửa/retire/merge.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(7142051963);

DO $catalog$
DECLARE
    v_org_code text := 'BIDV110B803';
    v_actor_name text := 'quantri.ban';
    v_org bigint;
    v_actor varchar(20);
    v_tag bigint;
    v_row record;
    v_inserted integer := 0;
    v_changed integer;
BEGIN
    -- 1. Resolve đúng đơn vị và người thực hiện đã được cấp quyền; không hardcode ID số.
    SELECT org_id INTO STRICT v_org FROM learning_hub.orgs
    WHERE code = v_org_code AND status = 'active';
    SELECT user_id INTO STRICT v_actor FROM learning_hub.users
    WHERE user_name = v_actor_name AND org_id = v_org AND status = 'active' AND NOT must_change_password;
    IF NOT EXISTS (
        SELECT 1 FROM learning_hub.user_roles ur JOIN learning_hub.roles r USING (role_id)
        WHERE ur.user_id = v_actor AND ur.active AND r.active
          AND ((r.code = 'SUPER_ADMIN' AND ur.scope = 'GLOBAL') OR (r.code = 'ADMIN'
            AND (ur.scope = 'GLOBAL' OR (ur.scope = 'ORG' AND ur.org_id = v_org))))
    ) AND NOT EXISTS (
        SELECT 1 FROM learning_hub.community_moderators
        WHERE user_id = v_actor AND org_id = v_org AND status = 'active'
    ) THEN
        RAISE EXCEPTION 'Actor phải có quyền ADMIN đúng scope hoặc moderator hiện hữu của đơn vị.';
    END IF;
    IF EXISTS (
        SELECT slug FROM learning_hub.community_tags WHERE owner_org_id = v_org GROUP BY slug HAVING count(*) > 1
    ) OR EXISTS (
        SELECT code FROM learning_hub.community_badges WHERE owner_org_id = v_org GROUP BY code HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Danh mục đang trùng grain trong đơn vị; cần xử lý trước, script không tự merge.';
    END IF;

    -- 2. Các tag dùng cho bộ lọc, gắn bài viết/câu hỏi và khai báo chuyên môn.
    FOR v_row IN SELECT * FROM (VALUES
        ('spring-boot', 'Spring Boot', 'Cấu hình và phát triển ứng dụng Spring Boot.'),
        ('liquibase', 'Liquibase', 'Quản lý thay đổi cấu trúc cơ sở dữ liệu.'),
        ('microservices', 'Microservices', 'Thiết kế, tích hợp và vận hành dịch vụ.'),
        ('java', 'Java', 'Ngôn ngữ Java và JVM.'),
        ('jhipster', 'JHipster', 'JDL, sinh ứng dụng và xử lý cấu hình JHipster.'),
        ('oracle', 'Oracle', 'SQL, UCP và kết nối cơ sở dữ liệu Oracle.'),
        ('angular', 'Angular', 'Ứng dụng frontend và tích hợp API.'),
        ('openshift', 'OpenShift', 'Triển khai và vận hành ứng dụng trên OpenShift.'),
        ('postgresql', 'PostgreSQL', 'Truy vấn và thiết kế dữ liệu PostgreSQL.'),
        ('api-design', 'API Design', 'Hợp đồng API, OpenAPI và xử lý lỗi.'),
        ('testing', 'Testing', 'Kiểm thử nghiệp vụ, tích hợp và hồi quy.'),
        ('security', 'Security', 'Phân quyền và bảo vệ dữ liệu ứng dụng.')
    ) AS s(slug, name, description) LOOP
        INSERT INTO learning_hub.community_tags
            (owner_org_id, slug, name, description, status, created_by, updated_by)
        SELECT v_org, v_row.slug, v_row.name, v_row.description, 'active', v_actor, v_actor
        WHERE NOT EXISTS (
            SELECT 1 FROM learning_hub.community_tags WHERE owner_org_id = v_org AND slug = v_row.slug
        );
        GET DIAGNOSTICS v_changed = ROW_COUNT;
        v_inserted := v_inserted + v_changed;
    END LOOP;

    -- 3. Alias chỉ gắn vào tag active chưa merge; không lấy lại tên đã thuộc tag khác trong cùng org.
    FOR v_row IN SELECT * FROM (VALUES
        ('spring-boot', 'springboot'), ('java', 'jvm'), ('jhipster', 'jdl'),
        ('oracle', 'oracle-ucp'), ('openshift', 'ocp'), ('api-design', 'openapi')
    ) AS s(slug, alias) LOOP
        SELECT tag_id INTO v_tag FROM learning_hub.community_tags
        WHERE owner_org_id = v_org AND slug = v_row.slug AND status = 'active' AND merged_into_tag_id IS NULL;
        IF v_tag IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM learning_hub.community_tag_aliases a
            JOIN learning_hub.community_tags t ON t.tag_id = a.tag_id
            WHERE t.owner_org_id = v_org AND a.normalized_alias = v_row.alias
        ) AND NOT EXISTS (
            SELECT 1 FROM learning_hub.community_tags
            WHERE owner_org_id = v_org AND slug = v_row.alias AND tag_id <> v_tag
        ) THEN
            INSERT INTO learning_hub.community_tag_aliases
                (tag_id, normalized_alias, created_by, updated_by)
            VALUES (v_tag, v_row.alias, v_actor, v_actor);
            v_inserted := v_inserted + 1;
        END IF;
    END LOOP;

    -- 4. Luật huy hiệu dùng allowlist của backend; không seed điểm hoặc award cho bất kỳ người nào.
    FOR v_row IN SELECT * FROM (VALUES
        ('COMMUNITY_ACCEPTED_ANSWER', 'Câu trả lời hữu ích', 'accepted_answers', 1),
        ('COMMUNITY_UPVOTED_10', 'Chia sẻ được ghi nhận', 'received_upvotes', 10),
        ('COMMUNITY_MAINTENANCE_3', 'Người chăm sóc tri thức', 'verified_maintenance', 3)
    ) AS s(code, name, metric, threshold) LOOP
        INSERT INTO learning_hub.community_badges
            (owner_org_id, code, name, description, rule_version, rule_json, status, created_by, updated_by)
        SELECT v_org, v_row.code, v_row.name, 'Luật khởi tạo; chỉ nguồn hợp lệ hiện tại được tính.',
            1, jsonb_build_object('metric', v_row.metric, 'threshold', v_row.threshold), 'active', v_actor, v_actor
        WHERE NOT EXISTS (
            SELECT 1 FROM learning_hub.community_badges WHERE owner_org_id = v_org AND code = v_row.code
        );
        GET DIAGNOSTICS v_changed = ROW_COUNT;
        v_inserted := v_inserted + v_changed;
    END LOOP;

    -- Audit chỉ khi thực sự bổ sung; chạy lại không sinh lịch sử thay đổi giả.
    IF v_inserted > 0 THEN
        INSERT INTO learning_hub.audit_log (actor_id, action, entity_type, entity_id, details)
        VALUES (v_actor, 'COMMUNITY_CATALOG_INITIALIZED', 'community_catalog', v_org::text,
            jsonb_build_object('ownerOrgId', v_org, 'script', 'learning_hub_community_catalog.sql',
                'insertedRows', v_inserted));
    END IF;
    RAISE NOTICE 'Community catalog: bổ sung % dòng; không thay đổi danh mục hiện hữu.', v_inserted;
END;
$catalog$;
COMMIT;
