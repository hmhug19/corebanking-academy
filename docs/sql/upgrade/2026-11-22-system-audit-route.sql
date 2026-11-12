-- The menu function SYSTEM_AUDIT ("Hệ thống & nhật ký") opens the gamification admin screen: the system log screen is
-- not built, and the app links the menu entry to /admin/gamification. The seed said /admin/system, so the assistant's
-- open_screen tool sent users to a placeholder. This points the function at the screen the menu really opens.
-- Only a row still holding the old seeded value is changed; a route an operator set by hand is left alone.
-- Safe to run again. No backend restart or schema change is involved.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-22-system-audit-route.sql
-- Rollback: docs/sql/upgrade/2026-11-22-system-audit-route.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

UPDATE learning_hub.functions
   SET route_pattern = '/admin/gamification'
 WHERE code = 'SYSTEM_AUDIT'
   AND route_pattern = '/admin/system';

COMMIT;
