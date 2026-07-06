# Kinetica documentation site — self-contained image.
# Build via docs/docker-build.sh (it stages the jar + bundled JS first), or manually:
#   ./kotlin package -m docs-site
#   node scripts/bundle-docs.mjs
#   docs/docker-build.sh
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY docs-site.jar /app/docs-site.jar
COPY bundles /app/bundles

ENV PORT=8080
ENV KINETICA_BUNDLES_DIR=/app/bundles
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["java", "-jar", "/app/docs-site.jar"]
