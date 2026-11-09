# 배포 중 공개 API 연결 관측

`Docker ECR Deploy`가 EC2 배포를 시작하기 전에 사용자 API와 관리자 API의 CloudFront health 주소를 2초 간격으로 관측합니다. 기존 배포 성공 여부를 판정하는 health gate와 별개인 진단 기록입니다. `hold` 모드에서는 실행하지 않습니다.

실패 시 JSONL에 다음 정보를 남깁니다.

- UTC 요청 시각, 서비스 이름, 소요 시간
- 수신한 HTTP 상태와 health 상태. 응답 body를 읽다가 실패해도 이미 받은 HTTP 코드는 유지합니다.
- 오류 종류와 전체 오류 메시지, 하위 원인 종류 `reasonType`, 하위 오류 내용 `reason`, 오류 번호 `errno`
- 실패 단계 `failureStage`: 응답 열기(DNS, TCP, TLS, 요청 전송과 헤더 수신), 응답 body 읽기, JSON 해석, HTTP 오류 응답
- 응답이 있을 때 CloudFront 요청 식별자 `cloudFrontRequestId`

오류 번호가 없는 예외는 `errno=null`로 기록합니다. 오류를 자동 재시도로 덮어쓰지 않고 각 관측을 그대로 남기며, 한 서비스의 실패가 다른 서비스 관측을 중단하지 않습니다. 자격증명, query와 fragment가 있는 관측 URL은 거부합니다.

배포 실패나 취소 경로에서도 `always()` 단계가 관측 중단을 요청합니다. 중단 파일을 만들고 보관한 모니터 PID의 종료를 확인하며, 15초 내 끝나지 않으면 TERM을 보내고 5초 후에도 살아 있으면 KILL로 종료합니다. 강제 종료는 경고를 남기며 이 경우 요약은 불완전할 수 있습니다.

JSONL, 서비스별 집계와 실행 로그는 `deployment-health-<run-id>-<attempt>` GitHub Actions 아티팩트에 7일 보관하며, 작업 요약에도 집계를 표시합니다. 관측 시작, 종료와 아티팩트 업로드 실패는 기존 배포 판정을 바꾸지 않습니다. 관측이 중간에 끝났거나 요약이 없으면 이를 작업 요약에서 확인할 수 있습니다. 정상 루프의 기본 최대 관측 시간은 110분입니다. `connectionFailures` 집계는 HTTP 응답 코드를 받지 못한 실패를 뜻하며 TCP 연결 장애만을 의미하지 않습니다.

러너 장애나 강제 취소로 정리 단계 자체가 실행되지 않으면 요약과 아티팩트 업로드는 보장할 수 없습니다. 각 관측은 JSONL에 즉시 flush하며, GitHub hosted runner는 작업 종료 시 남은 자식 프로세스를 정리합니다.

관측 위치는 GitHub Actions runner입니다. 이 기록만으로 모든 사용자 요청이 무중단이었다고 단정하지 않습니다. 배포 직전부터 종료까지 시각을 원점 로그와 대조하는 진단 자료로 사용합니다.

검증 명령:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
git diff --check
```
