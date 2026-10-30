-- Community menu functions: COMMUNITY for every role, COMMUNITY_MODERATION for ADMIN and SUPER_ADMIN.
-- Menu mapping only; community permissions and moderator scope stay in the community module.
-- Safe to rerun: inserts only the rows that are missing.
BEGIN;

INSERT INTO learning_hub.functions(code, name, route_pattern, sort_no)
SELECT v.code, v.name, v.route_pattern, v.sort_no
FROM (VALUES
    ('COMMUNITY'           , 'Cộng đồng'          , '/community'           , 165),
    ('COMMUNITY_MODERATION', 'Điều phối cộng đồng', '/community/moderation', 170)
) AS v(code, name, route_pattern, sort_no)
WHERE NOT EXISTS (SELECT 1 FROM learning_hub.functions f WHERE f.code = v.code);

INSERT INTO learning_hub.role_functions(role_id, function_id)
SELECT r.role_id, f.function_id
FROM learning_hub.roles r
CROSS JOIN learning_hub.functions f
WHERE ((f.code = 'COMMUNITY'
        AND r.code IN ('LEARNER', 'INSTRUCTOR', 'ADMIN', 'SUPER_ADMIN', 'REPORT_VIEWER'))
    OR (f.code = 'COMMUNITY_MODERATION' AND r.code IN ('ADMIN', 'SUPER_ADMIN')))
  AND NOT EXISTS (
      SELECT 1 FROM learning_hub.role_functions rf
      WHERE rf.role_id = r.role_id AND rf.function_id = f.function_id
  );

COMMIT;
