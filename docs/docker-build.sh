#!/usr/bin/env bash
# Builds the kinetica-docs Docker image: packages the site, bundles both client modules and
# the benchmark demo pages/report, stages artifacts into a minimal build context, and runs
# docker build. Requires `npm install` (or `npm ci`) in bench/ first.
# Set STAGE_ONLY=1 to stop after preparing docs/.docker-stage for docker buildx.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
stage="$repo_root/docs/.docker-stage"
image="${IMAGE:-kinetica-docs}"

cd "$repo_root"
# The compiler plugin is mandatory for every module and resolves via mavenLocal.
./kotlin publish mavenLocal -m kinetica-compiler
./kotlin package -m docs-site
node scripts/bundle-docs.mjs

# Behavior-identical Game of Life apps + committed trace report/results.
node scripts/build-game-of-life.mjs

# Benchmark demo pages + report, published alongside the docs site (see performance.md).
node bench/build.mjs
node bench/build-kinetica.mjs
node bench/build-compose.mjs
node scripts/bundle-bench-static.mjs

rm -rf "$stage"
mkdir -p "$stage/bundles"
cp build/tasks/_docs-site_executableJarJvm/docs-site-jvm-executable.jar "$stage/docs-site.jar"
cp -R build/tasks/_docs-site_assets "$stage/bundles/_docs-site_assets"
cp -R build/tasks/_docs-client_bundle "$stage/bundles/_docs-client_bundle"
cp -R build/tasks/_server-components-client_bundle "$stage/bundles/_server-components-client_bundle"
cp -R build/tasks/_bench_dist "$stage/bundles/_bench_dist"
cp -R build/tasks/_game-of-life_dist "$stage/bundles/_game-of-life_dist"
cp docs/Dockerfile "$stage/Dockerfile"

if [[ "${STAGE_ONLY:-0}" == "1" ]]; then
  echo "Staged Docker context: $stage"
  exit 0
fi

docker build -t "$image" "$stage"
echo
echo "Built image: $image"
echo "Run with:    docker run --rm -p 8080:8080 $image"
