#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo 'Usage: scripts/package-airgap.sh DESTINATION IMAGE_NAME IMAGE_TAG BUILDER_IMAGE [POSTGRES_IMAGE]' >&2
  exit 2
fi

destination=$1
image_name=$2
image_tag=$3
builder_image=$4
postgres_image=${5:-postgres:16-alpine}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -e "$destination" || -L "$destination" ]]; then
  echo "Destination already exists: $destination" >&2
  exit 2
fi
for image in "$image_name:$image_tag" "$builder_image" "$postgres_image"; do
  if [[ ! "$image" =~ ^[A-Za-z0-9][A-Za-z0-9_./:-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]; then
    echo "Use a tagged image reference: $image" >&2
    exit 2
  fi
  docker image inspect "$image" >/dev/null
done

# Create the kit next to its destination so it can be renamed after all checks succeed.
work=$(mktemp -d -- "${destination}.tmp.XXXXXXXX")
cleanup() {
  rm -f -- "$work/images.tar" "$work/compose.yml" "$work/compose.airgap.yml" "$work/images.env" "$work/AIRGAP.md" "$work/SHA256SUMS"
  rmdir -- "$work" 2>/dev/null || true
}
trap cleanup EXIT
docker image save --output "$work/images.tar" "$image_name:$image_tag" "$builder_image" "$postgres_image"
cp "$root/docker-compose.prod.yml" "$work/compose.yml"
cp "$root/deploy/compose.airgap.yml" "$work/compose.airgap.yml"
cp "$root/docs/AIRGAP.md" "$work/AIRGAP.md"
printf 'IMAGE_NAME=%s\nIMAGE_TAG=%s\nSDDP_MAVEN_BUILDER_IMAGE=%s\nSDDP_POSTGRES_IMAGE=%s\n' \
  "$image_name" "$image_tag" "$builder_image" "$postgres_image" > "$work/images.env"
(
  cd -- "$work"
  sha256sum images.tar compose.yml compose.airgap.yml images.env AIRGAP.md > SHA256SUMS
)
mv -T -- "$work" "$destination"
trap - EXIT
echo "Air-gapped install bundle ready: $destination"
