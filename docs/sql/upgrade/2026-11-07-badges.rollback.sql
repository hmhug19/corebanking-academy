-- Rollback of 2026-11-07-badges.sql: drops both tables; every badge definition and every badge earned is lost.
BEGIN;
SET LOCAL lock_timeout = '5s';
DROP TABLE IF EXISTS learning_hub.user_badges;
DROP TABLE IF EXISTS learning_hub.badge_definitions;
COMMIT;
