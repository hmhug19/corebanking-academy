#!/usr/bin/env bash
# Seeds the demo course DEMO-MEDIA on the local stack through the public API, signed in as the admin account:
#   upload the sample PDF and MP4 (each must become READY) -> field/programme/module -> course with one PDF lesson
#   and one video lesson -> publish -> assign to the demo learner.
# Does nothing when the course DEMO-MEDIA already exists. Never writes SQL: a READY asset has to pass the real probe.
#
# Needs: curl, jq, a running backend (default http://localhost:8019), docs/sql/demo-media/*.
# The password is read from LOCAL_DEMO_PASSWORD (or .env.local) and never printed or put on a command line.
#
# Usage: scripts/local-seed-media.sh       (env: BASE_URL, SEED_ADMIN, SEED_LEARNER)
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -z "${LOCAL_DEMO_PASSWORD:-}" && -f .env.local ]]; then
  LOCAL_DEMO_PASSWORD="$(grep -E '^LOCAL_DEMO_PASSWORD=' .env.local | head -1 | cut -d= -f2-)"
fi
BASE_URL="${BASE_URL:-http://localhost:${SERVER_PORT:-8019}}"
SEED_ADMIN="${SEED_ADMIN:-quantri}"
SEED_LEARNER="${SEED_LEARNER:-hocvien}"
COURSE_CODE="DEMO-MEDIA"
MEDIA_DIR="docs/sql/demo-media"

# `fail` is also called inside $(...), where bash 3.2 (macOS) drops `set -e`; the TERM trap stops the whole script.
trap 'exit 1' TERM
fail() {
  echo "FAIL  $*" >&2
  kill -TERM $$
  exit 1
}
step() { echo "==> $*"; }

command -v jq >/dev/null || fail "Cần jq (brew install jq)."
[[ -n "${LOCAL_DEMO_PASSWORD:-}" ]] || fail "Thiếu LOCAL_DEMO_PASSWORD (đặt trong .env.local hoặc môi trường)."
for f in demo-core-banking.pdf demo-core-banking.mp4; do
  [[ -f "$MEDIA_DIR/$f" ]] || fail "Thiếu $MEDIA_DIR/$f."
done

AUTH_HEADER="$(mktemp)"
chmod 600 "$AUTH_HEADER"
trap 'rm -f "$AUTH_HEADER"' EXIT

# api METHOD PATH [JSON_BODY]: prints the response body, fails unless `status` is SUCCESS.
api() {
  local method="$1" path="$2" body="${3:-}" out status
  if [[ -n "$body" ]]; then
    out="$(curl -sS --max-time 60 -X "$method" "$BASE_URL$path" -H "@$AUTH_HEADER" -H 'Content-Type: application/json' --data-binary "$body" 2>/dev/null || true)"
  else
    out="$(curl -sS --max-time 60 -X "$method" "$BASE_URL$path" -H "@$AUTH_HEADER" 2>/dev/null || true)"
  fi
  status="$(jq -r '.status // empty' <<<"$out" 2>/dev/null || true)"
  [[ "$status" == "SUCCESS" ]] || fail "$method $path -> $(jq -c '{status, messageCode, errors}' <<<"$out" 2>/dev/null || echo "${out:0:150}")"
  printf '%s' "$out"
}

health="$(curl -sS --max-time 10 "$BASE_URL/actuator/health" 2>/dev/null || true)"
[[ "$(jq -r '.status // empty' <<<"$health" 2>/dev/null || true)" == "UP" ]] || fail "backend không trả UP tại $BASE_URL."

step "Đăng nhập $SEED_ADMIN"
login="$(jq -n --arg u "$SEED_ADMIN" --arg p "$LOCAL_DEMO_PASSWORD" '{username: $u, password: $p}' \
  | curl -sS --max-time 15 -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' --data @- 2>/dev/null || true)"
token="$(jq -r '.data.accessToken // empty' <<<"$login" 2>/dev/null || true)"
[[ -n "$token" ]] || fail "đăng nhập $SEED_ADMIN thất bại ($(jq -r '.messageCode // empty' <<<"$login" 2>/dev/null || true))."
printf 'Authorization: Bearer %s\n' "$token" >"$AUTH_HEADER"

ORG_ID="$(api GET /api/v1/auth/me | jq -r '.data.user.orgId')"
LEARNER_ID="$(api GET "/api/v1/users?q=$SEED_LEARNER&page=0&size=20" \
  | jq -r --arg u "$SEED_LEARNER" '.data | (if type == "array" then . else (.items // .content // []) end) | map(select(.username == $u)) | .[0].userId // empty')"
[[ -n "$ORG_ID" && "$ORG_ID" != null ]] || fail "không lấy được tổ chức của $SEED_ADMIN."
[[ -n "$LEARNER_ID" ]] || fail "không tìm thấy học viên $SEED_LEARNER."

state="$(api GET "/api/v1/instructor/courses?page=0&size=50&q=$COURSE_CODE" \
  | jq -r --arg c "$COURSE_CODE" '[.data.items[] | select(.code == $c)][0].publicationState // empty')"
if [[ "$state" == "published" ]]; then
  echo "Khóa $COURSE_CODE đã có, bỏ qua."
  exit 0
elif [[ -n "$state" ]]; then
  fail "khóa $COURSE_CODE đã có nhưng chưa xuất bản ($state): lần chạy trước dừng giữa chừng. Khởi tạo lại bằng 'docker compose ... down -v'."
fi

# Upload one file and make sure it is READY. Prints the asset id. The upload call probes the file itself; if the
# answer is not final yet, wait for the asset in the library list (there is no GET by id).
upload() {
  local file="$1" out id status
  out="$(curl -sS --max-time 300 -X POST "$BASE_URL/api/v1/learning-assets?ownerOrgId=$ORG_ID" -H "@$AUTH_HEADER" \
    -F "file=@$file" 2>/dev/null || true)"
  [[ "$(jq -r '.status // empty' <<<"$out" 2>/dev/null || true)" == "SUCCESS" ]] \
    || fail "upload $file -> $(jq -c '{status, messageCode, errors}' <<<"$out" 2>/dev/null || echo "${out:0:150}")"
  id="$(jq -r '.data.id' <<<"$out")"
  status="$(jq -r '.data.status // empty' <<<"$out" | tr '[:upper:]' '[:lower:]')"
  for _ in $(seq 1 60); do
    case "$status" in
      ready) printf '%s' "$id"; return ;;
      failed) fail "$file: tài nguyên FAILED khi kiểm tra." ;;
    esac
    sleep 2
    status="$(api GET "/api/v1/learning-assets?ownerOrgId=$ORG_ID&page=0&size=100" \
      | jq -r --argjson id "$id" '[.data.items[] | select(.id == $id)][0].status // empty' | tr '[:upper:]' '[:lower:]')"
  done
  fail "$file: chưa READY sau 2 phút (trạng thái: ${status:-không rõ})."
}

step "Tải lên PDF mẫu"
PDF_ID="$(upload "$MEDIA_DIR/demo-core-banking.pdf")"
step "Tải lên video mẫu"
VIDEO_ID="$(upload "$MEDIA_DIR/demo-core-banking.mp4")"
echo "     PDF asset $PDF_ID, video asset $VIDEO_ID: READY"

# Find or create one catalog row (field, programme or module) by code. Prints its id.
catalog() {
  local kind="$1" code="$2" name="$3" field_id="${4:-null}" found filter=""
  [[ "$field_id" != null ]] && filter="&fieldId=$field_id"
  found="$(api GET "/api/v1/training-catalog/$kind?ownerOrgId=$ORG_ID$filter&page=0&size=100" \
    | jq -r --arg c "$code" '[.data.items[] | select(.code == $c)][0].id // empty')"
  if [[ -n "$found" ]]; then printf '%s' "$found"; return; fi
  api POST "/api/v1/training-catalog/$kind" "$(jq -n --argjson org "$ORG_ID" --argjson field "$field_id" --arg code "$code" --arg name "$name" \
    '{ownerOrgId: $org, fieldId: $field, code: $code, name: $name, description: "Dữ liệu demo cho môi trường local.", status: "published", sortNo: 0}')" \
    | jq -r '.data.id'
}

step "Danh mục: lĩnh vực, chương trình, phân hệ"
FIELD_ID="$(catalog fields DEMO-MEDIA-FIELD 'Demo tài liệu và video')"
# Programmes and modules carry the field id; the field row itself has none.
PROGRAM_ID="$(catalog programs DEMO-MEDIA-PROGRAM 'Chương trình demo media' "$FIELD_ID")"
MODULE_ID="$(catalog modules DEMO-MEDIA-MODULE 'Phân hệ demo media' "$FIELD_ID")"

step "Tạo khóa $COURSE_CODE"
created="$(api POST /api/v1/courses "$(jq -n --argjson org "$ORG_ID" --arg code "$COURSE_CODE" \
  '{ownerOrgId: $org, ownerTeamId: null, code: $code, title: "Demo tài liệu PDF và video"}')")"
COURSE_ID="$(jq -r '.data.course.id' <<<"$created")"
REVISION_ID="$(jq -r '.data.revision.id' <<<"$created")"
REVISION_VERSION="$(jq -r '.data.revision.rowVersion' <<<"$created")"

tree="$(jq -n --argjson ver "$REVISION_VERSION" --argjson program "$PROGRAM_ID" --argjson mod "$MODULE_ID" \
  --argjson pdf "$PDF_ID" --argjson video "$VIDEO_ID" '
  {
    expectedVersion: $ver, programId: $program, moduleId: $mod,
    title: "Demo tài liệu PDF và video",
    summary: "Khóa demo có một bài PDF và một bài video để thử màn học trên máy local.",
    level: "basic", defaultRequirement: "optional", coverAssetId: null, coverIcon: "file-text", coverColor: "#E3F1EF",
    objectives: ["Đọc tài liệu PDF nhiều trang.", "Xem video và theo dõi tiến độ."],
    rewardPoints: 0,
    chapters: [{
      title: "Chương 1: Tài liệu và video",
      lessons: [
        {title: "Bài 1: Tài liệu PDF", lessonType: "content", estimatedDurationSeconds: 300, isRequired: true,
         blocks: [{blockType: "pdf", title: "Tài liệu Core Banking", body: null, bodyFormat: null,
                   completionMode: "pdf_end", completionThreshold: null, isRequired: true, steps: [],
                   assets: [{assetId: $pdf, usage: "primary", caption: "Tài liệu mẫu 4 trang.", altText: null}]}]},
        {title: "Bài 2: Video", lessonType: "content", estimatedDurationSeconds: 30, isRequired: true,
         blocks: [{blockType: "video", title: "Video giới thiệu", body: null, bodyFormat: null,
                   completionMode: "video_coverage", completionThreshold: 90, isRequired: true, steps: [],
                   assets: [{assetId: $video, usage: "primary", caption: "Video mẫu 30 giây.", altText: null}]}]}
      ]
    }]
  }')"
step "Lưu nội dung và xuất bản"
saved="$(api PUT "/api/v1/courses/$COURSE_ID/revisions/$REVISION_ID" "$tree")"
version="$(jq -r '.data.revision.rowVersion // .data.rowVersion' <<<"$saved")"
api POST "/api/v1/courses/$COURSE_ID/revisions/$REVISION_ID/publish" "$(jq -n --argjson v "$version" '{expectedVersion: $v}')" >/dev/null

step "Giao khóa cho $SEED_LEARNER"
api POST "/api/v1/courses/$COURSE_ID/assignments" "$(jq -n --argjson rev "$REVISION_ID" --argjson org "$ORG_ID" --arg user "$LEARNER_ID" \
  '{revisionId: $rev, assigningOrgId: $org, title: "Demo tài liệu và video", startAt: "2020-01-01T00:00:00Z",
    dueAt: "2030-12-31T16:59:59Z", requirement: "mandatory", enrollmentMode: "continue", reminderEnabled: false,
    reminderDaysBefore: 3, idempotencyKey: "local-seed-demo-media-01",
    targets: [{targetType: "user", userId: $user, teamId: null, orgId: null, groupId: null, includeDescendants: false}]}')" >/dev/null

echo "Xong: khóa $COURSE_CODE (id $COURSE_ID) có PDF + video READY, đã giao cho $SEED_LEARNER."
