# AWS 계정 이전 런북

이 문서는 Rougether dev stack을 다른 AWS 계정으로 옮길 때의 순서와 완료 기준을 기록합니다.
Innovation Sandbox는 리스 만료 시 모든 리소스를 삭제하므로 DB, S3 assets, Terraform state는
반드시 계정 밖에도 암호화·접근 제한된 백업을 유지합니다.

## 1. 준비

1. 기존 계정 리소스와 Terraform state를 수정하지 않은 상태로 보존합니다.
2. 새 계정은 별도 AWS profile과 별도 checkout/worktree의 local state를 사용합니다.
3. 기본 VPC가 없는 계정은 `create_network = true`를 사용합니다.
4. 새 asset bucket이 필요하면 `create_asset_bucket = true`로 private S3와 CloudFront OAC를 함께 만듭니다.
5. `terraform plan -out=...` 결과에 예상하지 않은 변경이나 destroy가 없는지 확인한 뒤 saved plan만 적용합니다.

Terraform state에는 생성된 DB/JWT/admin 값이 들어갈 수 있으므로 저장소에 커밋하지 않습니다.

## 2. 기준 복제

최종 전환 전에 서비스 중단 없이 기준 복제본을 먼저 만듭니다.

- ECR: user-api, admin-api, batch의 검증된 동일 `linux/amd64` 이미지를 새 registry에 복사
- S3: 전체 key와 size를 비교하고 새 private bucket으로 동기화
- SSM: JWT, Firebase, Webex, Kakao, Apple 값을 출력하지 않고 새 계정의 같은 parameter 이름으로 복사
- RDS: 새 인스턴스에서 `sudo systemctl stop rougether-batch && sudo systemctl disable rougether-batch`를
  먼저 실행하고 `inactive`를 확인한 뒤, `mysqldump --single-transaction --quick`으로 만든 기준 dump를 새 RDS에 복원

새 DB의 핵심 table row count와 `flyway_schema_history`를 원본과 비교합니다. 이 단계에서는 새 batch를
다시 기동하지 않습니다. 기존 batch와 동시에 실행하면 알림이 중복 발송될 수 있습니다.

## 3. 전환 전 검증

- `terraform validate`
- `terraform plan -detailed-exitcode`가 exit code 0인 no-op
- EC2/SSM online, RDS private, EC2·RDS status check 정상
- user-api direct health 및 CloudFront HTTPS health 200
- admin-api와 batch는 인스턴스 내부 health 200
- asset CloudFront는 샘플 object 200, S3 public URL은 403
- IAM simulation에서 허용된 SSM parameter/S3 prefix만 `allowed`, 범위 밖은 `explicitDeny` 또는 `implicitDeny`
- 기존 계정 health가 계속 200인지 확인

## 4. 최종 cutover

이 단계부터는 짧은 write freeze가 필요합니다.

1. 새 인스턴스의 batch가 `disabled`·`inactive`인지 다시 확인합니다. 실행 중이면 먼저 중지·비활성화합니다.
2. 기존 user-api, admin-api, batch를 정지해 쓰기와 알림 발송을 멈춥니다.
3. 최종 DB dump를 만들고 새 RDS를 그 dump로 교체합니다.
4. S3를 마지막으로 한 번 더 동기화하고 key/size 차이가 0인지 확인합니다.
5. 핵심 table row count와 Flyway version을 다시 비교합니다.
6. GitHub Actions variable `AWS_DEPLOY_ROLE_ARN`을 새 Terraform output으로 변경합니다.
7. 모바일의 API/asset 기본 URL을 새 CloudFront 주소로 배포합니다.
8. 새 user-api, admin-api를 검증한 뒤 `sudo systemctl enable --now rougether-batch`로 batch를 마지막에 시작합니다.
9. direct/CloudFront health, OpenAPI, asset sample, 실제 로그인 smoke를 확인합니다.

## 5. 롤백과 정리

전환 직후에는 기존 계정을 삭제하거나 리스를 반납하지 않습니다. 문제가 생기면 새 batch를 먼저 멈추고,
모바일/API endpoint와 GitHub Actions role을 기존 값으로 되돌린 뒤 기존 서비스를 재기동합니다.

관찰 기간이 끝나고 데이터 백업과 새 배포가 모두 검증된 뒤에만 기존 유료 리소스 정리를 별도 작업으로
진행합니다. Sandbox 리스 만료 전에는 다음 계정 또는 외부 저장소로 다시 이전해야 합니다.
