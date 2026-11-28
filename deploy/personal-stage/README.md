# 개인 AWS 실기기 검증 배포

`.github/workflows/docker-publish.yml`의 수동 입력 `personal_stage_https=true`는 기존 합성 MySQL과 사용자 API에 HTTPS 진입점을 연결한다. 이 모드는 운영 검증·이미지 발행·EC2 배포 job과 별도 concurrency group을 사용한다. main에 push하면 기존 운영 배포가 실행되므로, 검토 중에는 승인된 스테이징 브랜치에서만 dispatch한다.

## 최초 연결

1. 개인 계정 서울 리전의 **Systems Manager → Fleet Manager → Managed nodes**에서 리허설 Lightsail을 확인한다. 표준 하이브리드 활성화로 등록한 Online 상태 Linux 노드 한 대의 `mi-…`가 필요하다. 미등록 호스트는 해당 서버를 관리하는 팀의 프로비저닝 경로로 SSM Agent를 최초 등록한다. 활성화 코드는 운영자에게만 전달한다. Advanced tier, 유료 인스턴스나 별도 네트워크 생성은 이 배포의 전제 조건이 아니다.
2. GitHub Environment `personal-aws-stage`에는 허용한 배포 브랜치와 검토자를 지정한다. 개인 계정의 GitHub OIDC 공급자와 역할은 해당 저장소 및 이 environment의 subject와 `sts.amazonaws.com` audience만 신뢰하도록 구성한다. SSM 명령은 서울의 AWS-RunShellScript 문서와 등록한 리허설 관리 노드 하나로 제한하고, Lightsail 포트 변경 권한은 리허설 인스턴스 ARN 하나에만 허용한다. 해당 역할의 CloudFormation 정의는 `role.yaml`에 있다. `StageNode`가 비어 있는 최초 생성 시 역할은 권한이 없다. 노드를 확인하면 같은 스택을 정확한 노드 ID로 갱신한다. 조회에는 Lightsail GetInstance/GetInstancePortStates와 SSM DescribeInstanceInformation/ListCommandInvocations가 필요하다.
3. Environment 변수에 `PERSONAL_STAGE_ACCOUNT_ID`, `PERSONAL_STAGE_ROLE_ARN`, `PERSONAL_STAGE_NODE_ID`를 설정한다. 테스트 Wi-Fi 공인 IPv4의 `PERSONAL_STAGE_WIFI_CIDR`(/32)은 Environment secret에 설정한다. 임의의 긴 테스트 코드를 팀 비밀번호 관리 도구에 생성한다. `htpasswd -nBC 12 rehearsal`의 프롬프트에 입력해 나온 bcrypt 해시 부분만 Environment secret `PERSONAL_STAGE_CODE_HASH`에 저장한다.
4. Docker ECR Deploy에서 승인한 브랜치와 개인 HTTPS 옵션만 선택한다. 작업은 AWS 계정, 인스턴스 태그, SSM 노드 사설 IP와 IMDS 인스턴스 ID를 대조한다. 기존 방화벽과 충돌하면 중단한다. HTTP는 무료 단기 IP 인증서의 ACME 검증에 사용하고 나머지 경로는 404로 응답한다. HTTPS는 지정한 Wi-Fi /32에서만 허용한다. Caddy 인증서 저장소는 컨테이너 재생성 간 유지된다.
5. 작업 성공 후 같은 Wi-Fi의 기기에서 `https://<Lightsail IPv4>/#<테스트 코드>`를 연다. “테스트 회원 로그인”을 눌러 신규 합성 회원과 내 정보 조회를 확인하고 한 줄 소개를 수정해 “저장 후 다시 읽기” 결과를 확인한다. 코드는 URL fragment로 전달되고 페이지가 열린 뒤 주소에서 제거된다. 세션은 페이지 메모리에만 보관한다. 테스트 코드로 기존 회원을 선택할 수 없다.
6. 네이티브 Google 로그인은 별도 테스트 빌드에서 `EXPO_PUBLIC_API_URL=https://<Lightsail IPv4>/api/v1`로 연결한다. 배포한 스토어 앱 및 운영 OTA의 소셜 로그인 결과는 이 브라우저 테스트와 별도로 기록한다. 이 검증 서버는 health, Google 로그인, 내 정보 읽기·저장만 제공한다.

## 장애 시

`/run/rougether-deploy.lock`을 획득한 뒤에만 대상 컨테이너를 갱신한다. 공개 인증서와 health 확인에 실패하면 앞서 성공한 구성을 다시 적용한다. 최초 설치 실패 때는 게이트웨이를 내리고 원래 합성 사용자 API 설정을 복구한다. AWS 포트 설정 작업이 실패하면 이 실행에서 추가한 규칙만 회수한다. 워크플로 강제 취소 또는 AWS 연결 단절 후에는 Lightsail의 80/443 규칙과 SSM 명령 완료 상태를 다시 확인한다.
