-- One-off migration of learning history into the XP ledger (gamification group A, ticket 02).
-- Replays, at their original times and with the weights of config version 1: lessons completed (once per source lesson),
-- quizzes passed (once per quiz, published and not deleted), the first-try bonus where the attempt history allows it
-- (attempt no. 1, passed, score at or above the version's threshold) and courses completed (the latest completed cycle's
-- own reward, once per course). Streak points are NOT migrated. Community XP is added by its own ticket.
-- Safe to run again: every row has the ledger's unique key (user, source kind, source ref) and ON CONFLICT DO NOTHING, so a
-- rerun writes nothing twice. Awards the backend already wrote since 2026-11-07-xp-ledger.sql keep their row.
-- Run AFTER docs/sql/upgrade/2026-11-07-xp-ledger.sql and before switching the totals to the ledger. Run it on a copy of
-- the database first and compare each user's ledger total with the old points minus the streak part.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-xp-ledger-backfill.sql
-- Undo (only rows this script wrote; the ledger is append-only, so the trigger must be disabled for the statement):
--   ALTER TABLE learning_hub.xp_ledger DISABLE TRIGGER trg_xp_ledger_append_only;
--   DELETE FROM learning_hub.xp_ledger WHERE reason = 'migration';
--   ALTER TABLE learning_hub.xp_ledger ENABLE TRIGGER trg_xp_ledger_append_only;
BEGIN;
SET LOCAL lock_timeout = '5s';

-- Version 1 is normally created by the backend from app.learning.points.*; create it with the defaults if it is not there yet.
INSERT INTO learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp, first_try_bonus_xp,
    first_try_threshold_percent, community_xp, community_daily_cap)
VALUES (1, 5, 30, 20, 90, 10, 30)
ON CONFLICT (version_no) DO NOTHING;

INSERT INTO learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref, config_version_id, reason)
SELECT e.user_id, v.lesson_xp, min(coalesce(p.completed_at, p.update_time)), 'LESSON',
       coalesce(l.source_lesson_id, l.lesson_id)::text, v.version_id, 'migration'
FROM learning_hub.lesson_progress p
JOIN learning_hub.course_enrollments e ON e.enrollment_id = p.enrollment_id
JOIN learning_hub.course_lessons l ON l.lesson_id = p.lesson_id
CROSS JOIN learning_hub.gamification_config_versions v
WHERE v.version_no = 1 AND v.lesson_xp > 0 AND p.status = 'completed'
GROUP BY e.user_id, coalesce(l.source_lesson_id, l.lesson_id), v.version_id, v.lesson_xp
ON CONFLICT (user_id, source_kind, source_ref) DO NOTHING;

INSERT INTO learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref, config_version_id, reason)
SELECT a.user_id, v.quiz_pass_xp, min(coalesce(a.submitted_at, a.graded_at)), 'QUIZ_PASS', a.quiz_id::text,
       v.version_id, 'migration'
FROM learning_hub.quiz_attempts a
JOIN learning_hub.quizzes z ON z.quiz_id = a.quiz_id
CROSS JOIN learning_hub.gamification_config_versions v
WHERE v.version_no = 1 AND v.quiz_pass_xp > 0 AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
  AND a.graded_at IS NOT NULL AND a.passed = true AND z.status = 'PUBLISHED' AND NOT z.is_deleted
GROUP BY a.user_id, a.quiz_id, v.version_id, v.quiz_pass_xp
ON CONFLICT (user_id, source_kind, source_ref) DO NOTHING;

INSERT INTO learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref, config_version_id, reason)
SELECT a.user_id, v.first_try_bonus_xp, min(coalesce(a.submitted_at, a.graded_at)), 'QUIZ_FIRST_TRY_BONUS',
       a.quiz_id::text, v.version_id, 'migration'
FROM learning_hub.quiz_attempts a
JOIN learning_hub.quizzes z ON z.quiz_id = a.quiz_id
CROSS JOIN learning_hub.gamification_config_versions v
WHERE v.version_no = 1 AND v.first_try_bonus_xp > 0 AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
  AND a.graded_at IS NOT NULL AND a.passed = true AND a.attempt_no = 1
  AND a.score_percent >= v.first_try_threshold_percent AND z.status = 'PUBLISHED' AND NOT z.is_deleted
GROUP BY a.user_id, a.quiz_id, v.version_id, v.first_try_bonus_xp
ON CONFLICT (user_id, source_kind, source_ref) DO NOTHING;

INSERT INTO learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref, config_version_id, reason)
SELECT c.user_id, c.reward, coalesce(c.completed_at, c.update_time), 'COURSE', c.course_id::text, v.version_id,
       'migration'
FROM (
    SELECT DISTINCT ON (e.user_id, e.course_id) e.user_id, e.course_id, e.completed_at, e.update_time,
           coalesce((SELECT max(r.reward_points) FROM learning_hub.course_completion_rules r
                     WHERE r.revision_id = e.revision_id), 0) AS reward
    FROM learning_hub.course_enrollments e
    WHERE e.status = 'completed'
    ORDER BY e.user_id, e.course_id, e.cycle_no DESC
) c
CROSS JOIN learning_hub.gamification_config_versions v
WHERE v.version_no = 1 AND c.reward > 0
ON CONFLICT (user_id, source_kind, source_ref) DO NOTHING;

COMMIT;
