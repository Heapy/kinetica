#!/usr/bin/env bash
# Builds the kinetica-docs Docker image: packages the site, builds both client bundles,
# stages artifacts into a minimal build context, and runs docker build.
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

docker build -t "$image" "$stage"
echo
echo "Built image: $image"
echo "Run with:    docker run --rm -p 8080:8080 $image"
