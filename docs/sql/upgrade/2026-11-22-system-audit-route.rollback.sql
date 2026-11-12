-- Rolls back 2026-11-22-system-audit-route.sql: SYSTEM_AUDIT points at /admin/system again.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-22-system-audit-route.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

UPDATE learning_hub.functions
   SET route_pattern = '/admin/system'
 WHERE code = 'SYSTEM_AUDIT'
   AND route_pattern = '/admin/gamification';

COMMIT;
