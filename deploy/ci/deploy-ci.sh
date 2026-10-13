#!/bin/bash
set -euo pipefail
umask 077
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
read -r image
read -r registry_user
read -r registry_token
[[ "$image" =~ ^ghcr.io/hmhug19/corebanking-academy@sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid image reference' >&2; exit 2; }
[[ "$registry_user" =~ ^[a-zA-Z0-9-]+$ ]] || { echo 'Invalid registry account' >&2; exit 2; }
test -n "$registry_token"
exec 9>/run/lock/core-academy-ci.lock
flock -w 300 9
base=/opt/core-academy/backend
config=/etc/core-academy-ci/compose.yaml
registry_config=$(mktemp -d /run/core-academy-registry.XXXXXX)
cleanup() { rm -f "$registry_config/config.json"; rmdir "$registry_config"; }
trap cleanup EXIT
printf '%s' "$registry_token" | docker --config "$registry_config" login ghcr.io --username "$registry_user" --password-stdin
unset registry_token
docker --config "$registry_config" pull "$image"
previous=$(docker inspect core-academy-backend-1 --format '{{.Config.Image}}' 2>/dev/null || true)
compose() { BACKEND_IMAGE="$1" docker compose --env-file "$base/.env.ubuntu" -f "$config" "${@:2}"; }
rollback() {
  if test -n "$previous"; then
    echo 'Restoring previous backend image' >&2
    compose "$previous" up -d --no-build --pull never --wait --wait-timeout 180 backend
    docker exec project-web-1 nginx -s reload
  fi
}
if ! compose "$image" up -d --no-build --pull never --wait --wait-timeout 180 backend; then
  compose "$image" logs --tail=60 backend >&2 || true
  rollback
  exit 1
fi
if ! curl --fail --silent --show-error http://127.0.0.1:8019/actuator/health | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'; then
  rollback
  exit 1
fi
if ! docker exec project-web-1 nginx -t || ! docker exec project-web-1 nginx -s reload; then
  rollback
  exit 1
fi
printf '%s\n' "$previous" > "$base/ci-previous-image"
printf '%s\n' "$image" > "$base/ci-active-image"
echo 'Backend deployment healthy'

