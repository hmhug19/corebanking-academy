-- Menu function TRAINING_CATALOG ("Lĩnh vực và chương trình", /admin/catalog): granted to ADMIN, SUPER_ADMIN and INSTRUCTOR.
-- Run once on a DB that already has the Identity baseline (a new DB gets this from learning_hub_identity.sql).
-- Safe to run again: it only adds what is missing. No existing row is changed.
-- Only the menu entry is added; what a user may change is still decided by scope and role checks in the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-05-training-catalog-function.sql
-- Rollback: docs/sql/upgrade/2026-11-05-training-catalog-function.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

INSERT INTO learning_hub.functions(code, name, route_pattern, sort_no)
SELECT v.code, v.name, v.route_pattern, v.sort_no
FROM (VALUES ('TRAINING_CATALOG', 'Lĩnh vực và chương trình', '/admin/catalog', 45)) AS v(code, name, route_pattern, sort_no)
WHERE NOT EXISTS (SELECT 1 FROM learning_hub.functions f WHERE f.code = v.code);

INSERT INTO learning_hub.role_functions(role_id, function_id)
SELECT r.role_id, f.function_id
FROM learning_hub.roles r
CROSS JOIN learning_hub.functions f
WHERE f.code = 'TRAINING_CATALOG'
  AND r.code IN ('ADMIN', 'SUPER_ADMIN', 'INSTRUCTOR')
  AND NOT EXISTS (
      SELECT 1 FROM learning_hub.role_functions rf WHERE rf.role_id = r.role_id AND rf.function_id = f.function_id);

COMMIT;
