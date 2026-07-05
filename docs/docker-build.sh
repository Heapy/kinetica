#!/usr/bin/env bash
# Builds the kinetica-docs Docker image: packages the site, builds both client bundles,
# stages artifacts into a minimal build context, and runs docker build.
# Set STAGE_ONLY=1 to stop after preparing docs/.docker-stage for docker buildx.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
stage="$repo_root/docs/.docker-stage"
image="${IMAGE:-kinetica-docs}"

cd "$repo_root"
./kotlin package -m docs-site
./kotlin build -m docs-client
./kotlin build -m server-components-client

rm -rf "$stage"
mkdir -p "$stage/bundles"
cp build/tasks/_docs-site_executableJarJvm/docs-site-jvm-executable.jar "$stage/docs-site.jar"
cp -R build/tasks/_docs-client_linkJs "$stage/bundles/_docs-client_linkJs"
cp -R build/tasks/_server-components-client_linkJs "$stage/bundles/_server-components-client_linkJs"
cp docs/Dockerfile "$stage/Dockerfile"

if [[ "${STAGE_ONLY:-0}" == "1" ]]; then
  echo "Staged Docker context: $stage"
  exit 0
fi

docker build -t "$image" "$stage"
echo
echo "Built image: $image"
echo "Run with:    docker run --rm -p 8080:8080 $image"
