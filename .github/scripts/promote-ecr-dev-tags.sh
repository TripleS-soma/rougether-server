#!/usr/bin/env bash
set -Eeuo pipefail

promote_ecr_dev_tags() {
  if [ "$#" -eq 0 ]; then
    echo "at least one ECR repository is required" >&2
    return 2
  fi

  local registry="${ECR_REGISTRY:?ECR_REGISTRY is required}"
  local repository_prefix="${ECR_REPOSITORY_PREFIX:?ECR_REPOSITORY_PREFIX is required}"
  local image_tag="${IMAGE_TAG:?IMAGE_TAG is required}"
  local deployed_sha="${GITHUB_SHA:?GITHUB_SHA is required}"
  local -a repositories=("$@")
  local -a previous_digests=()

  # 태그 승격은 ECR repo 별로 순차 적용된다. 일부 repo 만 새 SHA 를 가리키는 상태가
  # 남지 않도록 모든 기존 digest 를 먼저 확보하고, 중간 실패 시 전체 태그 세트를 복원한다.
  local index repo output
  for index in "${!repositories[@]}"; do
    repo="${repositories[$index]}"
    if output="$(
      aws ecr describe-images \
        --repository-name "${repository_prefix}/${repo}" \
        --image-ids imageTag="${image_tag}" \
        --query 'imageDetails[0].imageDigest' \
        --output text 2>&1
    )"; then
      if [[ "$output" != sha256:* ]]; then
        echo "invalid current :${image_tag} digest for ${repo}: ${output}" >&2
        return 1
      fi
      previous_digests[$index]="$output"
    elif grep -q "ImageNotFoundException" <<< "$output"; then
      previous_digests[$index]="ABSENT"
    else
      echo "failed to look up current :${image_tag} digest for ${repo}: ${output}" >&2
      return 1
    fi
  done

  restore_previous_dev_tags() {
    trap - ERR
    echo "promotion failed; restoring previous :${image_tag} tag set" >&2

    local restore_failed=false
    local digest
    for index in "${!repositories[@]}"; do
      repo="${repositories[$index]}"
      digest="${previous_digests[$index]}"
      if [ "$digest" = "ABSENT" ]; then
        # 원래 태그가 없던 repo 는 새로 붙은 태그를 제거해 원상복구한다.
        # BatchDeleteImage 는 HTTP 200 응답 안에도 failures 를 담을 수 있으므로 exit code 와
        # failures 를 모두 확인한다. 이미 태그가 사라진 ImageNotFound 는 원하는 최종 상태다.
        local delete_failures
        if delete_failures="$(
          aws ecr batch-delete-image \
            --repository-name "${repository_prefix}/${repo}" \
            --image-ids imageTag="${image_tag}" \
            --query "length(failures[?failureCode!='ImageNotFound'])" \
            --output text 2>&1
        )"; then
          if [ "$delete_failures" != "0" ]; then
            echo "failed to remove restored-absent :${image_tag} tag for ${repo}: ${delete_failures} ECR failure(s)" >&2
            restore_failed=true
          fi
        else
          echo "failed to remove restored-absent :${image_tag} tag for ${repo}: ${delete_failures}" >&2
          restore_failed=true
        fi
      else
        docker buildx imagetools create \
          --tag "${registry}/${repository_prefix}/${repo}:${image_tag}" \
          "${registry}/${repository_prefix}/${repo}@${digest}" || restore_failed=true
      fi
    done

    if [ "$restore_failed" = true ]; then
      echo "WARNING: :${image_tag} restore incomplete — verify the full tag set before any instance replace" >&2
    fi
  }
  trap restore_previous_dev_tags ERR

  for repo in "${repositories[@]}"; do
    docker buildx imagetools create \
      --tag "${registry}/${repository_prefix}/${repo}:${image_tag}" \
      "${registry}/${repository_prefix}/${repo}:${deployed_sha}"
  done

  trap - ERR
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  promote_ecr_dev_tags "$@"
fi
