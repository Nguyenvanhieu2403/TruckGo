#!/bin/bash
# One-shot MinIO setup, run with the root account by the minio-init service. Creates the private bucket and an
# application user that may only read/write objects in that bucket (no admin rights, no other buckets).
# Idempotent: safe to run on every `docker compose up`; re-running also applies a changed application secret.
set -eu

: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" "${MINIO_BUCKET:?}" "${MINIO_APP_ACCESS_KEY:?}" "${MINIO_APP_SECRET_KEY:?}"
if [ "${#MINIO_APP_SECRET_KEY}" -lt 8 ]; then echo "MINIO_APP_SECRET_KEY must be at least 8 characters" >&2; exit 1; fi
if [ "$MINIO_APP_ACCESS_KEY" = "$MINIO_ROOT_USER" ]; then echo "MINIO_APP_ACCESS_KEY must differ from the root user" >&2; exit 1; fi

export MC_CONFIG_DIR=/tmp/mc
mc alias set local "${MINIO_ENDPOINT:-http://minio:9000}" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "local/$MINIO_BUCKET"
mc anonymous set none "local/$MINIO_BUCKET" >/dev/null

policy=$(cat /init/truckgo-app-policy.json)   # the image has bash but no sed
printf '%s\n' "${policy//'${BUCKET}'/$MINIO_BUCKET}" > /tmp/truckgo-app-policy.json
mc admin policy create local truckgo-app /tmp/truckgo-app-policy.json >/dev/null
mc admin user add local "$MINIO_APP_ACCESS_KEY" "$MINIO_APP_SECRET_KEY" >/dev/null
mc admin policy attach local truckgo-app --user "$MINIO_APP_ACCESS_KEY" >/dev/null 2>&1 || true   # already attached
echo "MinIO ready: bucket $MINIO_BUCKET, application user $MINIO_APP_ACCESS_KEY (policy truckgo-app)"
