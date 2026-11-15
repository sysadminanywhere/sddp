#!/usr/bin/env bash
set -euo pipefail

case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1; root=$(pwd -W); null_output=NUL ;;
  *) root=$(pwd); null_output=/dev/null ;;
esac

suffix="$$-${RANDOM}"
network="sddp-gateway-test-${suffix}"
upstream="sddp-upstream-${suffix}"
gateway="sddp-gateway-${suffix}"
cleanup() {
  docker rm -f "$gateway" "$upstream" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker run -d --name "$upstream" --network "$network" --network-alias app \
  caddy:2-alpine caddy file-server --listen :8080 --root /usr/share/caddy >/dev/null
docker run -d --name "$gateway" --network "$network" -p 127.0.0.1::443 \
  -e SDDP_DOMAIN=localhost -e SDDP_USER=Bob \
  -e 'SDDP_PASSWORD_HASH=$2a$14$Zkx19XLiW6VYouLHR5NmfOFU0z2GTNmpkT/5qqR7hx4IjWJPDhjvG' \
  -v "$root/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine >/dev/null
port=$(docker port "$gateway" 443/tcp)
port=${port##*:}
request() {
  curl -qsk --connect-to "localhost:443:127.0.0.1:${port}" -o "$null_output" -w '%{http_code}' "$@" https://localhost/
}
status=000
for _ in {1..30}; do
  status=$(request || true)
  [[ "$status" == 401 ]] && break
  sleep 0.5
done
[[ "$status" == 401 ]]
[[ $(request -u Bob:hiccup) == 200 ]]
[[ $(request -u Bob:hiccup -X POST -H 'Origin: https://evil.example') == 403 ]]
[[ $(request -u Bob:hiccup -X POST) == 403 ]]
status=$(request -u Bob:hiccup -X POST -H 'Origin: https://localhost')
[[ "$status" != 403 && "$status" != 000 ]]
echo 'Gateway authentication and Origin checks passed'
