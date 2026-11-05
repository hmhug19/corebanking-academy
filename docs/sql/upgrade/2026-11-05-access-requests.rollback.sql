-- Rollback of 2026-11-05-access-requests.sql. Drops the table and with it every pending and resolved request.
-- It prints how many rows the table holds first, so the loss is visible in the psql output.
-- Deploy a backend without access requests first: it fails on startup once the table is gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-05-access-requests.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    request_rows bigint := 0;
BEGIN
    IF to_regclass('learning_hub.access_requests') IS NOT NULL THEN
        SELECT count(*) INTO request_rows FROM learning_hub.access_requests;
    END IF;
    RAISE NOTICE 'Dropping access_requests (% rows)', request_rows;
END
$report$;

DROP INDEX IF EXISTS learning_hub.uq_access_requests_pending;
DROP INDEX IF EXISTS learning_hub.idx_access_requests_status_time;
DROP TABLE IF EXISTS learning_hub.access_requests;

COMMIT;
