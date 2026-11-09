# 크리스마스 구성 마스터 운영

`christmas-event-launch.py`는 서버와 iOS 검증을 마친 운영자가 실행하는 도구입니다. 스크립트 작성 단계에서는 실제 DB에 연결하거나 변경하지 않았습니다.

## 모드와 변경 범위

| 모드 | 동작 |
| --- | --- |
| 생략 또는 `--check` | 읽기 전용 일관 스냅샷으로 검증하고 rollback합니다. stdout에는 구성 상태와 개수만 출력합니다. |
| `--stage` | 누락된 누적 출석을 비활성으로 생성하고, 테마 커버를 연결하며, 크리스마스10종의 직접 구매 금액과 재화를 NULL로 만듭니다. V101 머신의 theme_id가 NULL인 경우에만 정확한 테마를 연결합니다. |
| `--activate` | 완전한 STAGED 상태와 기간을 확인한 뒤 테마,10종 아이템,전용 뽑기,누적 출석을 단일 트랜잭션으로 활성화합니다. |

`--stage`와 `--activate`는 변경 전에 반드시 새 `--snapshot` 파일을 작성합니다. 권한은0600이며 기존 파일을 덮어쓰지 않습니다. 관련 행을 잠근 상태에서 검증하고, SQL 영향 행 수와 변경 후 전체 구성을 재검증한 뒤 한 번만 commit합니다. 실패하면 rollback합니다.

이미 활성화된 정확한 구성은 두 쓰기 모드에서 모두 NOOP입니다. 기존 활성 이벤트를 끄거나, 부분적으로 활성화된 상태를 임의 복구하지 않습니다. 부분 활성, 다른 커버, 다른 가격, 다른 기간/보상 등은 수동 검토가 필요한 드리프트로 거절합니다. 단계가 완료된 상태에서 같은 명령을 다시 실행하면 기존 snapshot 경로가 있어도 새 파일을 쓰지 않고 NOOP입니다. 실패 후 재시도에 변경이 필요한 경우에는 새 snapshot 파일명을 사용합니다.

이 스크립트는 SSM feature flag, 팝업 숨김, 알림, 사용자, 지갑, 출석 이력, 지급 이력을 변경하지 않습니다. DB 비밀번호는 SSM에서 메모리로만 가져오고 CLI 인자로 받지 않습니다. 비밀번호와 SDK/DB 예외 원문은 로그와 스냅샷에 남기지 않습니다.

## 고정 계약

- Flyway V100,V101 모두 적용, 전체 이력 실패0개, 최대 버전101 이상, 필요한 V100 컬럼 존재를 요구합니다.
- `--expected-checksum VERSION=SIGNED_CHECKSUM`가 전달되면 해당 적용 이력의 checksum도 일치해야 합니다. 운영 예시는100과101을 모두 지정합니다.
- 테마는 `snowy_christmas_202611`, 이름은 크리스마스입니다. 정확한10개 asset key로 아이템을 찾으며 숫자 ID는 환경별 조회 결과를 사용합니다.
- 패널 없는 벽지는 `items/snowy-christmas-202611/wallpaper/snowflake-wallpaper-panel-free-v2.png`입니다.
- 커버는 `themes/snowy-christmas-202611-cover.png`입니다. 기존 값이 NULL 또는 이 key일 때만 허용합니다.
- 직접 판매 설정은 기존 비활성 스테이징의10DIAMOND 또는 이미 NULL인 상태만 허용합니다. stage 후 금액과 재화가 모두 NULL이어야 합니다.
- 전용 머신 `christmas_2026`은 V101이 생성해야 합니다. 25COIN,count1,KST11/09 00:00부터12/26 00:00 미포함이며 전용10종 풀은 V101의5/3/2종 등급과 weight1을 그대로 검증합니다. 이 도구는 풀 행을 만들거나 지우지 않습니다.
- 기존 카테고리 머신은 코드 `wallpaper_gacha`, `floor_gacha`, `furniture_gacha`로 조회합니다. 모두 활성25COIN,count1,기간 없음 상태를 요구합니다.
- 기존 후보는 itemId로 중복 제거한159종, 일반109/희귀36/전설14입니다. 카테고리별15/15/129종이며, asset key와 등급과 카테고리 코드의 정렬 집합 해시도11/09 12:00 KST preflight 기준과 일치해야 합니다.
- 현재 서버 카테고리는 DB 컬럼이 아니라 GachaCategory.fromCode로 정한 정확한3개 코드입니다. 운영 도구와 Java enum의 코드 집합 일치도 테스트합니다. 서버와 동일하게 모든 출석 이벤트의 전용 reward_item_id를 일반 후보에서 제외한 후159종 집합을 확인합니다.
- 기준 SHA-256: `75db0faae01d364af4e115a9e0f44e1dd691caca628678709d634db19cdde56a`
- 활성 카탈로그에만 있고 기존 풀에 없던 벽지18종을 자동 편입하지 않습니다. 크리스마스 아이템이 전용 머신 외 어떤 풀에도 있으면 비활성 행이어도 거절합니다.
- 출석 코드는 소문자 `christmas_2026`, 제목은 크리스마스, KST11/09~12/25, CUMULATIVE30일, 일일30코인,5일마다 해당 일 총액60코인입니다.
- factory에 맞춰 bonus_day30,bonus_coin_amount30, reward_item_id NULL,generation_credit_amount0을 검증합니다. 특별 보상은 NONE입니다.
- 최초 활성화는 오늘 KST 날짜+29일이12/25 이하인 동안만 가능합니다. 따라서11/26을 포함하며11/27부터 거절합니다. 이벤트 시작 전이나12/26 이후에도 최초 활성화하지 않습니다. 이미 활성화된 정확한 구성의 NOOP는 기간 후에도 가능합니다.

## 실행

Python3.9 이상, boto3, PyMySQL과 신뢰할 RDS CA 파일이 필요합니다. 실행 환경의 IAM 권한은 SSM 비밀번호 읽기 및 필요한 DB master 권한으로 준비합니다. 터널을 사용할 경우 DB의 실제 호스트명을 `--db-host`에 유지하고 접속 주소만127.0.0.1로 지정합니다. TLS SNI/호스트 검증과 인증서 검증은 계속 켜져 있습니다.

아래 변수는 운영자가 현재 환경에 맞춰 준비합니다. DB 비밀번호를 shell 변수나 인자로 만들지 않습니다. feature flag 변경과 SSM 터널 생성/종료는 부모 운영 절차가 담당합니다.

```bash
christmas_db_args=(
  --db-host "$CHRISTMAS_DB_HOST"
  --connect-host 127.0.0.1
  --connect-port "$CHRISTMAS_DB_LOCAL_PORT"
  --ca-file "$CHRISTMAS_RDS_CA_FILE"
  --aws-profile rougether-isb
  --expected-checksum "100=$CHRISTMAS_V100_CHECKSUM"
  --expected-checksum "101=$CHRISTMAS_V101_CHECKSUM"
)

# 기본은 check이며 DB master를 변경하지 않습니다.
python3 deploy/scripts/christmas-event-launch.py "${christmas_db_args[@]}"

# V100/101 배포 및 전체 검증을 마친 다음 운영자가 실행합니다.
python3 deploy/scripts/christmas-event-launch.py "${christmas_db_args[@]}" \
  --stage --snapshot "$CHRISTMAS_STAGE_SNAPSHOT"

python3 deploy/scripts/christmas-event-launch.py "${christmas_db_args[@]}" --check

# 서버, 모바일, 운영 feature flag 절차가 승인된 후 부모 운영자가 실행합니다.
python3 deploy/scripts/christmas-event-launch.py "${christmas_db_args[@]}" \
  --activate --snapshot "$CHRISTMAS_ACTIVATE_SNAPSHOT"

python3 deploy/scripts/christmas-event-launch.py "${christmas_db_args[@]}" --check
```

직접 접속이면 `--connect-host`와 `--connect-port`를 생략합니다. EC2 IAM role을 쓰면 `--aws-profile`을 생략합니다. `--password-ssm-param` 기본값은 기존 dev DB 파라미터이며 다른 환경에서는 운영자가 명시합니다. 스크립트는 API health, iOS 빌드, feature flag, CDN 파일을 대신 검증하지 않으므로 그 절차는 부모 배포 흐름에서 완료해야 합니다.

## 로컬 검증

```bash
python3 -m unittest discover -s deploy/scripts/tests -p 'test_*christmas*.py' -v
python3 deploy/scripts/christmas-event-launch.py --help
```

launch42개와 flags22개, 총64개 로컬 테스트를 통과했습니다. launch 테스트는 기본 읽기 전용, 스키마/checksum, 정확한 asset/풀/등급 집합, 출석 전용 보상 제외, Java 카테고리 일치, 누적 출석 보상,11/26 경계, 전용 테마 연결, 활성 NOOP, 부분 활성 거절, snapshot 덮어쓰기 금지, 행 수 불일치, SQL 실패/사후 검증 실패 시 rollback, 비밀 오류 마스킹을 검증합니다. DB 드라이버와 SQL의 실제 운영 실행은 아직 하지 않았습니다.

## 환경 플래그 도구

`configure-christmas-flags.py`는 실행 중인 기존 dev API 호스트만 허용하며 AWS RunShellScript의 root 권한을 확인한 후 `/etc/rougether/user-api.env`의 CHRISTMAS_EVENT_ENABLED,CHRISTMAS_COIN_BONUS_ENABLED 두 키만 함께 변경합니다. 서비스나 컨테이너는 재시작하지 않습니다. 부모 운영 절차가 DB 활성화, 배포 및 재시작 순서를 관리합니다.

파일은 bytes로 읽으므로 CRLF와 비UTF-8 값까지 보존하고 기존 키 위치에서 값만 교체합니다. 없는 키를 새로 추가할 때만 기존 줄바꿈 형식의 구분자를 덧붙입니다. 누락 키의 실효 기본값은 false입니다. 따라서 disable 시 두 키가 없거나 이미 false라면 NOOP이며 파일과 백업을 새로 만들지 않습니다. flagsExplicitlyPresentBefore 출력으로 명시 여부를 구분합니다.

변경이 필요하면 원본 uid,gid,mode를 보존한 임시 파일을 만든 뒤 atomic replace합니다. 첫 백업은 `.before-christmas`이며 항상0600입니다. 과거 백업과 새 원본 내용이 다르면 과거 파일을 덮지 않고 별도의0600 백업을 만듭니다. 기존 백업의 소유자나 보안 권한이 맞지 않으면 자동 수정하지 않고 거절합니다. 원본/백업 symlink나 hardlink, group/world-write 권한, 중복/비정규 키 선언, 동시 원본 수정도 거절합니다. 다른 환경 변수나 비밀값은 출력하지 않습니다.

flags22개 테스트는 로컬 tempfile과 모의 AWS CLI만 사용했습니다. enable,disable,NOOP,CRLF/비밀 바이트 보존,원본 mode/소유자와 백업0600,중복 키,백업 재사용/보존,심볼릭/하드 링크,동시 수정,atomic replace 실패,root 요구와 무재시작을 검증합니다. 실제 SSM 실행이나 환경 파일 변경은 하지 않았습니다.
