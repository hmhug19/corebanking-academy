#!/usr/bin/env bash
# Smoke check of the backend running on the local stack (default http://localhost:8019).
# Checks health, signs in as the demo learner, then calls three read APIs; each must answer `status: SUCCESS`.
# Prints one line per check and stops with a non-zero exit code on the first failure.
#
# Needs: curl, jq. The password is read from LOCAL_DEMO_PASSWORD (or .env.local) and is never printed or put on a
# command line; the token lives in a mode-600 temp file that is deleted on exit.
#
# Usage: scripts/local-smoke.sh            (env: BASE_URL, SMOKE_USER)
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -z "${LOCAL_DEMO_PASSWORD:-}" && -f .env.local ]]; then
  LOCAL_DEMO_PASSWORD="$(grep -E '^LOCAL_DEMO_PASSWORD=' .env.local | head -1 | cut -d= -f2-)"
fi
BASE_URL="${BASE_URL:-http://localhost:${SERVER_PORT:-8019}}"
SMOKE_USER="${SMOKE_USER:-hocvien}"

fail() {
  echo "FAIL  $*" >&2
  exit 1
}

command -v jq >/dev/null || fail "Cần jq (brew install jq)."
[[ -n "${LOCAL_DEMO_PASSWORD:-}" ]] || fail "Thiếu LOCAL_DEMO_PASSWORD (đặt trong .env.local hoặc môi trường)."

AUTH_HEADER="$(mktemp)"
chmod 600 "$AUTH_HEADER"
trap 'rm -f "$AUTH_HEADER"' EXIT

# 1. Health.
health="$(curl -sS --max-time 10 "$BASE_URL/actuator/health" 2>/dev/null || true)"
[[ "$(jq -r '.status // empty' <<<"$health" 2>/dev/null || true)" == "UP" ]] \
  || fail "health: backend không trả UP tại $BASE_URL (đã chạy 'mvn spring-boot:run' với .env.local chưa?)."
echo "ok    health UP ($BASE_URL)"

# 2. Sign in. The body goes through stdin, so the password never appears in the process list.
login="$(jq -n --arg u "$SMOKE_USER" --arg p "$LOCAL_DEMO_PASSWORD" '{username: $u, password: $p}' \
  | curl -sS --max-time 15 -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' --data @- 2>/dev/null || true)"
token="$(jq -r '.data.accessToken // empty' <<<"$login" 2>/dev/null || true)"
if [[ -z "$token" ]]; then
  code="$(jq -r '.messageCode // .status // empty' <<<"$login" 2>/dev/null || true)"
  fail "login: đăng nhập $SMOKE_USER thất bại (${code:-không có phản hồi})."
fi
printf 'Authorization: Bearer %s\n' "$token" >"$AUTH_HEADER"
echo "ok    login $SMOKE_USER"

# 3. Read APIs as the learner.
check() {
  local name="$1" path="$2" filter="$3" body status detail
  body="$(curl -sS --max-time 20 -H "@$AUTH_HEADER" "$BASE_URL$path" 2>/dev/null || true)"
  status="$(jq -r '.status // empty' <<<"$body" 2>/dev/null || true)"
  [[ "$status" == "SUCCESS" ]] || fail "$name: $path trả status='${status:-không có}' (${body:0:120})."
  detail="$(jq -r "$filter" <<<"$body" 2>/dev/null || true)"
  echo "ok    $name SUCCESS${detail:+ ($detail)}"
}

check catalog "/api/v1/me/learning/catalog" \
  '(.data | if type == "array" then length else (.items // .content // [] | length) end | tostring) + " mục"'
check quiz-attempts "/api/v1/me/quiz-attempts" \
  '(.data | if type == "array" then length else (.items // .content // [] | length) end | tostring) + " lượt"'
check progress-summary "/api/v1/me/learning/progress/summary" '(.data.counts.total | tostring) + " khóa được giao"'

echo "Smoke OK: 5/5"
