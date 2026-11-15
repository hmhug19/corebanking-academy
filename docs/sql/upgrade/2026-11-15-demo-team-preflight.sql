-- CHỈ ĐỌC: rà phạm vi chuẩn hóa nhóm demo trước khi quyết định migration.
-- Chạy trên bản sao dữ liệu bằng role có quyền đọc; không chứa mật khẩu hoặc token.
-- Snapshot học viên biểu thị đơn vị tại thời điểm giao/học, không tự ghi đè.
BEGIN READ ONLY;
SELECT o.org_id, o.code, o.name, o.org_type, o.parent_id, o.status
FROM learning_hub.orgs o
WHERE o.code IN ('DEMO-SHOWCASE-V1', 'DEMO-SHOWCASE-OPS')
ORDER BY o.org_id;
SELECT t.team_id, t.code, t.name, t.org_id, o.code AS org_code
FROM learning_hub.teams t JOIN learning_hub.orgs o ON o.org_id = t.org_id
WHERE t.code = 'DEMO-SHOWCASE-TEAM';
SELECT u.user_id, u.user_name, u.org_id, u.team_id, u.status
FROM learning_hub.users u JOIN learning_hub.teams t ON t.team_id = u.team_id
WHERE t.code = 'DEMO-SHOWCASE-TEAM' ORDER BY u.user_id;
SELECT ur.ur_id, ur.user_id, r.code AS role_code, ur.scope, ur.org_id, ur.team_id, ur.active
FROM learning_hub.user_roles ur JOIN learning_hub.roles r ON r.role_id = ur.role_id
WHERE ur.user_id IN (SELECT u.user_id FROM learning_hub.users u JOIN learning_hub.teams t ON t.team_id = u.team_id
                     WHERE t.code = 'DEMO-SHOWCASE-TEAM')
   OR ur.team_id IN (SELECT team_id FROM learning_hub.teams WHERE code = 'DEMO-SHOWCASE-TEAM')
ORDER BY ur.user_id, ur.ur_id;
SELECT c.course_id, c.code, c.owner_org_id, c.owner_team_id, c.owner_user_id,
       rev.revision_id, rev.program_id, p.owner_org_id AS program_org_id, p.field_id,
       f.owner_org_id AS field_org_id
FROM learning_hub.courses c
LEFT JOIN learning_hub.course_revisions rev ON rev.course_id = c.course_id
LEFT JOIN learning_hub.training_programs p ON p.program_id = rev.program_id
LEFT JOIN learning_hub.training_fields f ON f.field_id = p.field_id
WHERE c.owner_team_id IN (SELECT team_id FROM learning_hub.teams WHERE code = 'DEMO-SHOWCASE-TEAM')
ORDER BY c.course_id, rev.revision_id;
SELECT 'course_enrollments' AS source, count(*) AS total FROM learning_hub.course_enrollments
WHERE team_id_snapshot IN (SELECT team_id FROM learning_hub.teams WHERE code = 'DEMO-SHOWCASE-TEAM')
UNION ALL
SELECT 'assignment_recipients', count(*) FROM learning_hub.assignment_recipients
WHERE team_id_snapshot IN (SELECT team_id FROM learning_hub.teams WHERE code = 'DEMO-SHOWCASE-TEAM')
UNION ALL
SELECT 'assignment_targets', count(*) FROM learning_hub.assignment_targets
WHERE team_id IN (SELECT team_id FROM learning_hub.teams WHERE code = 'DEMO-SHOWCASE-TEAM');
COMMIT;
