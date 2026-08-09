#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLOUDFRONT_TF="$REPO_ROOT/deploy/terraform/ec2/cloudfront.tf"
MAIN_TF="$REPO_ROOT/deploy/terraform/ec2/main.tf"
OUTPUTS_TF="$REPO_ROOT/deploy/terraform/ec2/outputs.tf"
VARIABLES_TF="$REPO_ROOT/deploy/terraform/ec2/variables.tf"
PACKER_UNIT="$REPO_ROOT/deploy/packer/files/rougether-admin-api.service"
DEPLOY_SCRIPT="$REPO_ROOT/.github/scripts/deploy-ec2-with-rollback.sh"
DEPLOY_WORKFLOW="$REPO_ROOT/.github/workflows/docker-publish.yml"
APPLICATION_YML="$REPO_ROOT/admin-api/src/main/resources/application.yml"
ORIGIN_FILTER="$REPO_ROOT/admin-api/src/main/java/com/triples/rougether/adminapi/global/config/AdminOriginVerificationFilter.java"
LOGIN_RATE_FILTER="$REPO_ROOT/admin-api/src/main/java/com/triples/rougether/adminapi/global/config/AdminLoginRateLimitFilter.java"

assert_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  if ! grep -Fq -- "$pattern" "$file"; then
    echo "not ok - $message" >&2
    return 1
  fi
}

assert_contains "$CLOUDFRONT_TF" 'resource "aws_cloudfront_distribution" "admin_api"' \
  "admin API must have a dedicated CloudFront distribution"
assert_contains "$CLOUDFRONT_TF" 'comment         = "${local.name} admin-api HTTPS entrypoint"' \
  "admin distribution comment must match deployment discovery"
assert_contains "$CLOUDFRONT_TF" 'cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id' \
  "admin responses must not be cached"
assert_contains "$CLOUDFRONT_TF" 'origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id' \
  "admin sessions, CSRF tokens, and uploads must reach the origin"
assert_contains "$CLOUDFRONT_TF" 'response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id' \
  "admin responses must include managed security headers"
assert_contains "$CLOUDFRONT_TF" 'viewer_protocol_policy = "redirect-to-https"' \
  "admin viewers must be redirected to HTTPS"
assert_contains "$CLOUDFRONT_TF" 'name = "com.amazonaws.global.cloudfront.origin-facing"' \
  "CloudFront origin-facing managed prefix list must be resolved"
assert_contains "$CLOUDFRONT_TF" 'name  = "X-Rougether-Admin-Origin"' \
  "admin distribution must inject its origin verification header"
assert_contains "$CLOUDFRONT_TF" 'value = random_password.admin_origin.result' \
  "origin verification header must use the Terraform-managed secret"

assert_contains "$MAIN_TF" 'resource "aws_vpc_security_group_ingress_rule" "admin_api_cloudfront"' \
  "admin origin must have an explicit CloudFront-only ingress rule"
assert_contains "$MAIN_TF" 'prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin.id' \
  "admin ingress must use the CloudFront managed prefix list"
assert_contains "$VARIABLES_TF" 'condition     = length(var.allowed_admin_api_cidrs) == 0' \
  "direct admin CIDR ingress must remain forbidden"

assert_contains "$PACKER_UNIT" '--network host' \
  "baked admin unit must preserve loopback identity for emergency SSM access"
assert_contains "$DEPLOY_SCRIPT" '--network host' \
  "routine deployments must preserve loopback identity for local health checks"
assert_contains "$OUTPUTS_TF" 'value       = "https://${aws_cloudfront_distribution.admin_api.domain_name}/login"' \
  "admin_url must expose the permanent HTTPS login URL"
assert_contains "$OUTPUTS_TF" 'value       = "https://${aws_cloudfront_distribution.admin_api.domain_name}/admin/health"' \
  "admin_health_url must use CloudFront HTTPS"

assert_contains "$APPLICATION_YML" 'same-site: strict' \
  "production admin session cookies must use SameSite Strict"
assert_contains "$APPLICATION_YML" 'secure: true' \
  "production admin session cookies must be HTTPS-only"
assert_contains "$APPLICATION_YML" 'use-relative-redirects: true' \
  "admin login redirects must stay on the CloudFront host"
assert_contains "$ORIGIN_FILTER" 'MessageDigest.isEqual' \
  "admin origin secret must use constant-time comparison"
assert_contains "$ORIGIN_FILTER" 'isLoopback(request.getRemoteAddr())' \
  "SSM emergency access must remain available through loopback"
assert_contains "$LOGIN_RATE_FILTER" 'DEFAULT_MAX_ATTEMPTS = 10' \
  "public admin login must have an attempt limit"
assert_contains "$LOGIN_RATE_FILTER" 'response.setStatus(429)' \
  "excessive admin login attempts must be rejected"
assert_contains "$CLOUDFRONT_TF" "event.request.headers['x-rougether-viewer-ip']" \
  "CloudFront Function must overwrite the trusted viewer IP header"
assert_contains "$CLOUDFRONT_TF" 'function_arn = aws_cloudfront_function.admin_viewer_ip.arn' \
  "admin distribution must attach the viewer IP function"
assert_contains "$LOGIN_RATE_FILTER" 'VIEWER_IP_HEADER = "X-Rougether-Viewer-Ip"' \
  "login rate limiting must use only the CloudFront-managed viewer IP header"
assert_contains "$DEPLOY_SCRIPT" 'refresh_admin_origin_secret_env' \
  "routine deployments must refresh the origin secret from SSM"
assert_contains "$DEPLOY_WORKFLOW" "Comment=='rougether-dev admin-api HTTPS entrypoint'" \
  "deploy workflow must discover the admin distribution"
assert_contains "$DEPLOY_WORKFLOW" 'https://${admin_cf_domain}/admin/health' \
  "deploy workflow must verify the permanent admin endpoint"

echo "admin CloudFront tests passed"
