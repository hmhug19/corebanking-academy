#!/usr/bin/env bash
# Loads every baseline and demo SQL into an EMPTY local database, in the documented order.
# Local/dev only: the demo files must never run on a shared or production database.
#
# Usage (from the repository root, after the db container is healthy):
#   scripts/local-db-init.sh
#
# Reads .env.local when present. The password comes from PGPASSWORD or DB_PASSWORD and is never printed.
# Host, port, user and database default to the compose `db` service and can be overridden with
# PGHOST, PGPORT, PGUSER and PGDATABASE.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -f .env.local ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.local
  set +a
fi

export PGHOST="${PGHOST:-127.0.0.1}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-core_academy}"
export PGDATABASE="${PGDATABASE:-core_academy}"
export PGPASSWORD="${PGPASSWORD:-${DB_PASSWORD:-}}"
export PGOPTIONS="${PGOPTIONS:--c client_min_messages=warning}"

if [[ -z "$PGPASSWORD" ]]; then
  echo "Thiếu mật khẩu DB: đặt PGPASSWORD hoặc DB_PASSWORD (trong .env.local)." >&2
  exit 1
fi

case "$PGHOST" in
  localhost | 127.0.0.1 | ::1) ;;
  *)
    echo "Từ chối chạy: PGHOST=$PGHOST không phải máy local. Script này chỉ dành cho DB local." >&2
    exit 1
    ;;
esac

# Order: Identity -> Learning -> media/progress -> Assessment -> Knowledge (pgvector) -> Community -> menu functions ->
# catalog -> demos (assessment demo needs the learning demo) -> verify. Assessment needs Identity and Learning only; it sits before Knowledge and Community.
FILES=(
  learning_hub_identity.sql
  learning_hub_learning.sql
  learning_hub_media_progress.sql
  learning_hub_assessment.sql
  upgrade/2026-11-07-flashcards.sql
  upgrade/2026-11-07-personal-flashcards.sql
  learning_hub_knowledge.sql
  upgrade/2026-11-10-assistant-conversations.sql
  upgrade/2026-11-10-assistant-pending-actions.sql
  upgrade/2026-11-10-assistant-feedback.sql
  upgrade/2026-11-11-knowledge-document-scope.sql
  upgrade/2026-11-11-course-content-index.sql
  learning_hub_community.sql
  learning_hub_community_functions.sql
  learning_hub_community_catalog.sql
  learning_hub_learning_demo.sql
  learning_hub_assessment_demo.sql
  learning_hub_learner_quiz_demo.sql
  learning_hub_community_demo.sql
  learning_hub_community_verify.sql
)

# Wait for the server, then refuse a database that already holds data.
for _ in $(seq 1 30); do
  psql -qAtc "select 1" >/dev/null 2>&1 && break
  sleep 1
done
if ! psql -qAtc "select 1" >/dev/null 2>&1; then
  echo "Không kết nối được DB $PGUSER@$PGHOST:$PGPORT/$PGDATABASE. Đã chạy docker compose up -d db chưa?" >&2
  exit 1
fi

if [[ "$(psql -qAtc "select to_regclass('learning_hub.users') is not null")" == "t" ]]; then
  echo "DB đã có dữ liệu (learning_hub.users tồn tại): không chạy lại. Xóa volume bằng 'docker compose ... down -v' nếu muốn khởi tạo lại." >&2
  exit 1
fi

for f in "${FILES[@]}"; do
  echo "==> docs/sql/$f"
  if [[ "$f" == *_verify.sql ]]; then
    psql -q -v ON_ERROR_STOP=1 -f "docs/sql/$f"
  else
    psql -q -v ON_ERROR_STOP=1 -f "docs/sql/$f" >/dev/null
  fi
done

echo "Xong: ${#FILES[@]} file SQL đã chạy trên $PGDATABASE."
