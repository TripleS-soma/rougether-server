#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ASSETS_TF="$REPO_ROOT/deploy/terraform/ec2/assets.tf"
MAIN_TF="$REPO_ROOT/deploy/terraform/ec2/main.tf"
VARIABLES_TF="$REPO_ROOT/deploy/terraform/ec2/variables.tf"
BUG_REPORT_TEMPLATE="$REPO_ROOT/admin-api/src/main/resources/templates/bug-reports.html"
DEPLOY_SCRIPT="$REPO_ROOT/.github/scripts/deploy-ec2-with-rollback.sh"
DEPLOY_WORKFLOW="$REPO_ROOT/.github/workflows/docker-publish.yml"

assert_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  if ! grep -Fq "$pattern" "$file"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_not_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  if grep -Fq "$pattern" "$file"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_contains "$VARIABLES_TF" 'variable "asset_public_read_prefixes"' \
  "public CDN prefixes must be configured separately from writable prefixes"
assert_contains "$VARIABLES_TF" 'condition     = var.create_asset_bucket' \
  "external buckets must be rejected until private access can be guaranteed"
assert_contains "$VARIABLES_TF" 'contains([' \
  "Terraform validation must restrict CDN reads to approved public prefixes"
PUBLIC_PREFIX_BLOCK="$(sed -n '/variable "asset_public_read_prefixes"/,/^}/p' "$VARIABLES_TF")"
if grep -Fq 'bug-reports/*' <<< "$PUBLIC_PREFIX_BLOCK"; then
  echo "not ok - bug report screenshots must not be in the default public CDN prefixes" >&2
  exit 1
fi
assert_contains "$ASSETS_TF" 'for prefix in var.asset_public_read_prefixes' \
  "CloudFront bucket policy must allow only explicit public prefixes"
assert_contains "$ASSETS_TF" 'path_pattern           = "profile/*"' \
  "profile images must have a dedicated cache behavior"
assert_contains "$ASSETS_TF" 'cache_policy_id        = data.aws_cloudfront_cache_policy.caching_disabled.id' \
  "profile images must not remain in CloudFront cache after deletion"
assert_contains "$MAIN_TF" 'asset_purge_versions              = tostring(var.create_asset_bucket)' \
  "the required managed versioned bucket must enable permanent profile deletion"
assert_contains "$BUG_REPORT_TEMPLATE" '/admin/bug-reports/screenshots?key=' \
  "admin screenshot previews must use the authenticated application endpoint"
assert_not_contains "$BUG_REPORT_TEMPLATE" "s3BaseUrl + '/' + key" \
  "bug report screenshots must not use the public CDN base URL"
assert_contains "$DEPLOY_SCRIPT" 'refresh_social_auth_env' \
  "routine deploys must refresh social-auth and purge settings on existing instances"
assert_contains "$DEPLOY_WORKFLOW" '__APPLE_PRIVATE_KEY_PARAMETER_NAME__' \
  "the deploy workflow must inject the Apple private-key SSM parameter name"
assert_not_contains "$DEPLOY_WORKFLOW" 'http://${public_ip}:8081/admin/health' \
  "the workflow must not call the localhost-only admin API through the public IP"

echo "asset privacy tests passed"
