# Rougether scale seed fixtures

이 폴더는 Rougether 로컬 scale runner 전용 합성 fixture를 담당합니다.
외부 서비스는 호출하지 않고, 백엔드 애플리케이션 코드는 변경하지 않습니다.

## Seed SQL과 fixture JSON

결정적 SQL과 fixture metadata를 생성합니다.

```bash
python3 qa/scale/seed/prepare.py \
  --users 1000 \
  --todo-count 10000 \
  --date 2026-09-09 \
  --jwt-secret "$ROUGETHER_SCALE_JWT_SECRET" \
  --output qa/scale/results/seed
```

`prepare.py`는 `seed.sql`, `fixtures.json`, `tokens.json`을 씁니다. 부모 runner가
`MYSQL_PWD`와 `docker compose exec -T ... mysql`을 소유하고, `seed.sql`만 격리된
`rougether_scale` DB에 적용합니다.

fixture는 다음 산술 mapping을 사용합니다.

- `todoId = todoStartId + writeOrdinal`
- `ownerUserId = userStartId + (writeOrdinal % usersCount)`

그래서 100,000명 규모에서도 per-todo JSON을 만들지 않습니다. token은 실행 편의를 위해
`users: [{id, token}]`에 포함하지만, test-only `JWT_SECRET`으로만 생성합니다.

## DB audit

snapshot SQL을 만들고, 부모 runner가 실행한 TSV 결과를 다시 audit합니다.

```bash
python3 qa/scale/seed/audit.py \
  --fixture qa/scale/results/seed/fixtures.json \
  --summary qa/scale/results/write-summary.json \
  --emit-snapshot-sql > qa/scale/results/snapshot.sql

python3 qa/scale/seed/audit.py \
  --fixture qa/scale/results/seed/fixtures.json \
  --summary qa/scale/results/write-summary.json \
  --snapshot qa/scale/results/snapshot.tsv \
  --out qa/scale/results/db-audit.json
```

summary는 전체 planned input이 drop 없이 전달됐음을 증명해야 성공 판정을 받을 수
있습니다. 그 조건이 모호하면 `passed=false`로 기록하고, DB invariant만 함께 보고합니다.

지원하는 최소 summary 형태:

```json
{
  "date": "2026-09-09",
  "config": {
    "scenario": "write",
    "plannedIterations": 10000,
    "droppedIterations": 0,
    "completionSuccess": 10000
  }
}
```

`scenario=read`는 완료 기대값 0, `write`는 planned 전체, `mixed`는
`floor(plannedIterations / 5)`, `contention`은 `hotTodoCount`를 완료 기대값으로
사용합니다. 완료된 todo id는 기대 prefix와 정확히 일치해야 하며, per-key 원장 중복,
user/source swap, per-user reward/growth/wallet 불일치를 실패로 기록합니다.
