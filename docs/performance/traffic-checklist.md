# 대용량 트래픽 대응 체크리스트

[비교 대상 글](https://gall.dcinside.com/mgallery/board/view/?id=backend&no=56747)의 번호와 순서를 따른다. 적용 범위는 서버 저장소와 보관된 테스트 자료에서 확인한 범위다. 미확인은 검증 기록이 없다는 뜻이다.

| 번호 | 글의 항목 | 상태 | 우리 서비스에서 확인한 범위 |
|---:|---|---|---|
| 1 | 사용자 입장 대기열 | 부분 | 가구 사진 요청은 동시 접수 한도를 넘으면 503과 Retry-After를 돌려준다. 순번을 발급하고 기다린 사용자를 들여보내는 대기열은 확인되지 않았다. [업로드 필터](https://github.com/TripleS-soma/rougether-server/blob/11085a40/user-api/src/main/java/com/triples/rougether/userapi/furniture/web/FurnitureUploadAdmissionFilter.java) |
| 2 | 웹/API 서버 수평 확장 | 실험 완료 | ALB 뒤에서 ECS 사용자 API를 1·2·4대로 늘려 1,000 RPS 분산과 미발행 0건을 확인했다. 수동 증설 회차다. [QA #494](https://github.com/TripleS-soma/rougether-server/issues/494) |
| 3 | 배치·관리자와 사용자 서버 분리 | 적용·일부 장애 검증 | 별도 앱/프로세스다. 배치 단독 중단 중 사용자 API가 응답했고, 재가동 후 밀린 미션의 만료가 한 번 반영됐다. 개발 배포는 같은 EC2 호스트를 쓰므로 호스트 장애는 공유한다. [배치 복구 #493](https://github.com/TripleS-soma/rougether-server/issues/493) |
| 4 | 읽기 복제본과 읽기/쓰기 분리 | 미구현 | 측정 구조는 공유 MySQL 한 대다. t4g→m6g는 수직 확장 실험이다. [RDS 구성](https://github.com/TripleS-soma/rougether-server/blob/11085a40/deploy/terraform/ec2/main.tf) |
| 5 | 집계 결과 캐싱 | 부분 | 주간 리포트 결과 저장과 일별 활동 중복 기록을 막는 캐시는 있다. 관리자 통계 조회 결과를 조건별 TTL로 캐시하고 주기적으로 갱신하는 구성은 확인되지 않았다. [운영 지표 정의](https://github.com/TripleS-soma/rougether-server/blob/11085a40/docs/operations/product-metrics.md) |
| 6 | SQL·인덱스 튜닝 | 실험 완료 | 내 정보 반복 요청의 SELECT 3→2회. 집 목록 쿼리와 인덱스 후보는 동일 응답을 대조하고 실측했다. 집 목록 전체 p99 개선까지 확인되지는 않았다. [집 목록 #501](https://github.com/TripleS-soma/rougether-server/issues/501) |
| 7 | 쓰기 잠금 범위 축소 | 수정·검증 | 서로 다른 회원의 첫 INSERT에서 교착을 재현했다. 생성권은 회원 행을 먼저 잠그고 계좌를 생성한다. 초대·집 선택은 격리 수준과 동일 회원 직렬화로 중복을 방지한다. 이번 검증은 빈 인덱스 구간의 교착 사례다. [PR #503](https://github.com/TripleS-soma/rougether-server/pull/503) |
| 8 | 부가 로그를 주 DB에서 분리 | 부분 | 접근 로그는 Nginx, 실험 앱 로그는 CloudWatch에서 읽었다. 일별 활동 기록, 보상 원장, 업무 감사처럼 서비스 상태와 연결된 데이터는 MySQL에 둔다. 별도 MongoDB/검색엔진 로그 파이프라인은 확인되지 않았다. [활동 기록](https://github.com/TripleS-soma/rougether-server/blob/11085a40/docs/work/activity-batch-rollout.md) |
| 9 | 대량 쓰기를 묶고 나누기 | 적용 | 일별 활동 writer가 한 번에 최대 256건을 받아 날짜별 회원 ID를 모아 INSERT…SELECT로 저장한다. [writer](https://github.com/TripleS-soma/rougether-server/blob/11085a40/user-api/src/main/java/com/triples/rougether/userapi/activity/service/UserDailyActivityWriter.java) |
| 10 | 전문 검색엔진과 CDC | 미구현 | MySQL 변경을 Kafka/CDC로 전달해 Elasticsearch/OpenSearch 검색 인덱스를 만드는 경로는 확인되지 않았다. |
| 11 | 집계·정렬·페이지 처리를 SQL에 위임 | 적용 | 댓글 수·투두 날짜별 집계는 GROUP BY를 사용하고, 집 목록 페이지와 건수를 DB에서 조회한다. [투두 저장소](https://github.com/TripleS-soma/rougether-server/blob/11085a40/domain/src/main/java/com/triples/rougether/domain/routine/repository/TodoRepository.java) |
| 12 | 무거운 작업을 큐로 넘기기 | 적용 | 사진으로 가구 만들기는 API가 작업을 접수하고 SQS와 Lambda가 후속 처리를 담당한다. [가구 처리 PR #461](https://github.com/TripleS-soma/rougether-server/pull/461) |
| 13 | 장애 시 정상 노드로 전환 | 부분 | ECS API 두 대에 약 60 HTTP RPS를 보내며 한 대를 중단했다. ALB에서 정상 대상이 하나였던 58.89초간 요청 3,533건에서 예상 밖 오류는 0건이었다. 교체 태스크는 99.82초에 healthy가 됐고 전체 투두 1,473개의 보상을 각각 한 번씩 확인했다. 배치 중단 실험도 있다. dev DB의 Terraform은 multi_az=false다. DB의 보조 노드 승격과 전환 중 쓰기 정합성은 검증하지 않았다. [배포 구성](https://github.com/TripleS-soma/rougether-server/blob/11085a40/deploy/terraform/ec2/main.tf) |
| 14 | 알림·이메일 분리 및 미디어 CDN | 부분 | 에셋용 S3·CloudFront 구성과 FCM 푸시·배치 발송은 있다. 모든 발송을 전담하는 독립 알림/메일 서버와 동영상 전송 경로는 확인되지 않았다. [에셋 CDN](https://github.com/TripleS-soma/rougether-server/blob/11085a40/deploy/terraform/ec2/assets.tf) |

[API 태스크 중단 실험 보고서](../../qa/scale/reports/2026-11-08-api-task-loss.md)
