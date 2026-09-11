# 가구 생성 Lambda 서비스 전환 (2026-09-11)

기존 앱의 multipart 접수와 응답 계약을 유지하고, AI 실행을 SQS와 Lambda로 분리한다. 직접 업로드와 전처리 Lambda는 이번 배포에서 비활성화한다.

- AI 함수 및 SQS 동시 실행 상한: 50
- DB 제어 함수 상한: 16. RDS max_connections=61이며, 배포 전 관측 연결은 약 33개다. AI 함수에는 DB 자격 증명을 주입하지 않는다.
- API 동시 이미지 접수: 2, 전체 미완료 작업: 100, 대기 만료: 5분
- 이미지 모델: gpt-image-2.5-flare, 특징 추출·제작 지침·검수: gpt-6-astra
- 원본·후보 private prefix의 1일 만료와 noncurrent version 만료는 기존 S3 정책을 유지한다.
- CloudWatch 큐 나이·DLQ·Lambda 오류·throttle 상태를 기록한다. SNS 수신처가 없어 외부 알림 전송은 설정하지 않았다.

## 근거와 검증 범위

이전 격리 실험에서 실제 Lambda 및 모의 AI HTTP 동시 50개, 100개 작업의 정산 정합성을 확인했다. VPC 내부 API의 부하 중 p95는 두 회차에서 약 11.8ms/10.5ms였고 일반 API 5,112건이 모두 성공했다. 실제 OpenAI 전체 흐름은 동시 2/4/8, 총 14건까지 확인했다. 실제 OpenAI 동시 50건의 처리량을 측정했다는 뜻은 아니다. 서비스 제어 함수를 16개 초기화한 후 읽기 전용 명령 50건을 한꺼번에 보낸 검사에서는 SDK 재시도 28회가 있었고, 50건 모두 약 1.16초에 완료됐다. 이는 AI 전체 처리량 검사가 아니다.

서비스에 옮긴 코드에서 전체 Gradle 테스트 2,080개 중 2,073개 통과, opt-in 등 7개 제외, 실패·오류 0개를 확인했다. API bootJar와 AI/control ZIP을 함께 빌드했다. 실서비스 공개 API에서 사진 2건을 동시에 접수해 큐부터 정산·보관함까지 확인했다. 맥 미니 52.4초, 곰 케이크 58.6초에 성공했고 각 1회 생성·검수, 생성권 차감 1회, 보관함 지급 1회였다. 출력은 1024×1024 RGBA PNG다. AI 함수 최대 메모리는 289MB, 제어 함수는 736MB였다. 세부 원자료와 최종 상태는 원 저장소 output/releases/furniture-service-20260911에 기록한다.

## Migration

실제 DB의 V69(개인 방 성장), V70(최고 레벨)은 이미 적용되어 변경하지 않는다. main의 아직 미적용된 알림 삭제 migration과 실험 브랜치의 가구 migration 번호 충돌을 다음과 같이 정리했다.

| 새 번호 | 내용 |
| --- | --- |
| V71 | notification.deleted_at 및 조회 인덱스 |
| V72 | 전역 실행·접수 잠금용 capacity |
| V73 | Lambda execution 및 outbox |
| V74 | 기본 비활성인 직접 업로드용 nullable metadata |

이전 실험 보고서의 V69~V71은 당시 실험 브랜치 번호다. 기존 서비스의 적용 이력을 repair하거나 수정하지 않는다.

## 배포와 복구

Terraform은 비공개 S3 remote state와 DynamoDB 잠금을 사용한다. service.tfvars.example은 비밀이 없는 설정 예시이며 DB 암호는 SSM에서 TF_VAR_db_password로만 주입한다. state와 plan에도 암호가 들어가므로 공유·커밋하지 않는다. 최초 plan은 enable_trigger=false로 만든다.

API를 blue/green으로 교체하는 동안 신규 가구 생성·피드백만 잠시 막고 기존 작업을 비운다. 새 API는 dispatcher=true로 시작하되 DB 실행 모드가 RESIDENT이면 메시지를 보내지 않는다. 구 API가 종료된 뒤 DB를 LAMBDA/50/실행 허용으로 바꾸고 SQS 연결을 활성화한다. 일반 API는 health 검증 후 Nginx upstream을 전환한다. admin과 batch 이미지는 이번 전환에서 유지한다.

문제가 있으면 가구 접수와 SQS를 먼저 정지하고 이미 실행 중인 작업을 종료·정산한 뒤 이전 API 이미지와 env로 복구한다. 실행 중인 유료 작업을 초기 상태로 돌려 재호출하지 않는다. 이번 migration은 테이블·nullable 열 추가이며 rollback 시 삭제하지 않는다. 재배포가 구 워커를 되살리지 않도록 배포한 커밋과 이미지 digest를 기록한다.

## 서비스 적용 결과

- 적용 커밋: `640892370d2c5e1bcfd50e4322a8be4035af8ebf`, user-api green
- API 이미지 digest: `sha256:1e7c9eaf3e952a31ec1b2fb6aa01b3046cfbd1aa9278dd9f3fe5c4b1c2b272aa`
- 가구 접수는 전환 동안 잠시 중단한 뒤 재개했다. admin 이미지와 배치 이미지는 유지했고, 메모리 여유를 확보하기 위해 잠시 멈춘 배치는 정상 복구했다.
- `rougether-dev-furniture-ai` reserved=50, `rougether-dev-furniture-control` reserved=16, SQS maximum=50/Enabled, DB LAMBDA/50/enabled를 재조회했다.
- 실제 검증 2건이 모두 성공했고 jobs/DLQ 큐가 비었다. 서비스 health도 공개 CloudFront 주소에서 성공했다.
- 재실행 시 `-var=enable_trigger=true`를 명시해야 현재 활성 설정을 유지한다. `service.tfvars.example`의 false는 최초 배포용 안전 기본값이다.
- 실험 함수와 실서비스 함수는 별개다. 실험 함수를 다시 활성화하지 않았다.
