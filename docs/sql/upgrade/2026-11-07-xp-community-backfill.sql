-- One-off replay of past community contributions into the XP ledger (gamification group A, ticket 03).
-- Awards COMMUNITY XP, at the contribution's original time and with the weights of config version 1, for every accepted
-- answer (accepted_answer) and helpful mark (received_upvote) that is still credited in community_contribution_events.
-- One row per contribution (source ref = content:reason:actor); at most community_daily_cap XP per learner per
-- Asia/Ho_Chi_Minh day, counting what the ledger already holds that day. Asking a question earns nothing.
-- Safe to run again: the ledger's unique key and ON CONFLICT DO NOTHING mean a rerun writes nothing twice.
-- Run AFTER 2026-11-07-xp-ledger.sql (and the lesson/quiz backfill, if used).
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-xp-community-backfill.sql
-- Undo (only rows this script wrote; the ledger is append-only, so the trigger must be disabled for the statement):
--   ALTER TABLE learning_hub.xp_ledger DISABLE TRIGGER trg_xp_ledger_append_only;
--   DELETE FROM learning_hub.xp_ledger WHERE source_kind = 'COMMUNITY' AND reason = 'migration';
--   ALTER TABLE learning_hub.xp_ledger ENABLE TRIGGER trg_xp_ledger_append_only;
BEGIN;
SET LOCAL lock_timeout = '5s';

INSERT INTO learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp, first_try_bonus_xp,
    first_try_threshold_percent, community_xp, community_daily_cap)
VALUES (1, 5, 30, 20, 90, 10, 30)
ON CONFLICT (version_no) DO NOTHING;

WITH pending AS (
  SELECT DISTINCT ON (ref) user_id, occurred_at, ref FROM (
    SELECT e.user_id, e.occurred_at, e.contribution_event_id,
           e.content_id::text || ':' || e.reason_code || ':' || o.actor_user_id AS ref
    FROM learning_hub.community_contribution_events e
    JOIN learning_hub.community_outbox_events o ON o.event_id = e.source_event_id
    WHERE e.points_delta > 0 AND e.reason_code IN ('accepted_answer', 'received_upvote')
      AND o.actor_user_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM learning_hub.community_contribution_events r
                      WHERE r.reverses_event_id = e.contribution_event_id)
  ) x
  ORDER BY ref, occurred_at, contribution_event_id
), ranked AS (
  SELECT p.user_id, p.occurred_at, p.ref,
         (p.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
         row_number() OVER (PARTITION BY p.user_id, (p.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                            ORDER BY p.occurred_at, p.ref) AS rn
  FROM pending p
  WHERE NOT EXISTS (SELECT 1 FROM learning_hub.xp_ledger l WHERE l.user_id = p.user_id
                    AND l.source_kind = 'COMMUNITY' AND l.source_ref = p.ref)
)
INSERT INTO learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref, config_version_id, reason)
SELECT r.user_id, least(v.community_xp, v.community_daily_cap - coalesce(prior.total, 0) - v.community_xp * (r.rn - 1)),
       r.occurred_at, 'COMMUNITY', r.ref, v.version_id, 'migration'
FROM ranked r
CROSS JOIN learning_hub.gamification_config_versions v
LEFT JOIN LATERAL (
  SELECT sum(l.amount) AS total FROM learning_hub.xp_ledger l
  WHERE l.user_id = r.user_id AND l.source_kind = 'COMMUNITY'
    AND (l.occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date = r.day
) prior ON true
WHERE v.version_no = 1 AND v.community_xp > 0
  AND least(v.community_xp, v.community_daily_cap - coalesce(prior.total, 0) - v.community_xp * (r.rn - 1)) > 0
ON CONFLICT (user_id, source_kind, source_ref) DO NOTHING;

COMMIT;
