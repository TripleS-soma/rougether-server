# 투두 날짜 조건의 복합 인덱스 탐색

누적 데이터가 있는 사용자에서는 날짜 조건까지 좁히는 복합 인덱스가 효과적이었습니다. 기존 인덱스도 사용되고 있었지만 사용자 범위까지만 좁힌 뒤 날짜를 필터링했습니다. 과거 투두 10,000건을 가진 사용자에게 후보 인덱스를 추가하자 오늘 조회의 인덱스 읽기 행은 10,003 → 3건, 일일 보상 합계 조회는 10,003 → 1건으로 줄었습니다.

이번 결과는 격리 DB의 SQL 실행 계획 비교입니다. 운영 migration, 애플리케이션 코드 및 실제 서비스 데이터는 변경하지 않았습니다. HTTP 처리량 개선이나 쓰기 비용까지 검증한 결과는 아닙니다.

## 현재 부하 시험과의 차이

앞선 pool 20 / VU 1,000 회차 `20260910T114024-816515-mixed`의 투두 조회 digest는 30,211회, 평균 약 0.170ms, 평균 검사 6행 / 반환 3행이었습니다. 사용자당 투두가 3개뿐이므로 누적 기록을 많이 읽는 비용이 작았습니다. 이 작은 fixture에서 발생한 3,000 RPS 미달의 주원인을 인덱스로 확정하지 않습니다.

이번에는 동일 JAR로 스키마를 생성하고 같은 사용자 20,000명·투두 60,000건을 복원한 다음, 한 사용자에게 과거 완료 투두 10,000건을 추가했습니다. 전체 투두는 70,000건이고 해당 사용자의 투두는 10,003건입니다. 조회 조건에 맞는 오늘 투두는 계속 3건, 오늘 완료 보상 합계는 10으로 유지했습니다.

## 실행 계획으로 확인한 후보

| 쿼리 | 후보 인덱스 | 기존 인덱스에서 읽은 행 | 후보 적용 후 읽은 행 | SQL 실행 시간 중앙값 |
| --- | --- | ---: | ---: | --- |
| 오늘 투두 조회 | `todos(user_id, deleted_at, due_date)` | 10,003 | 3 | 7.38 → 0.0219ms |
| 일일 투두 보상 합계 | `todos(user_id, status, completed_at)` | 10,003 | 1 | 6.47 → 0.0242ms |

시간은 각 조건에서 연속 3회 실행한 `EXPLAIN ANALYZE`의 최상위 노드 완료 시간 중앙값입니다. 행 수는 해당 인덱스 접근 노드의 실제 반환 행 수입니다. HTTP p95, 커넥션 대기, 시스템 처리량과 다른 지표이며, 세 번의 SQL 실행으로 장시간 성능이나 효과 크기의 통계적 재현성을 주장하지 않습니다.

원본 3건 데이터에서는 오늘 조회가 `uk_todos_user_external`의 `user_id` 선두 부분을 사용해 3건을 읽었고, 보상 조회는 `idx_todos_user_status`로 완료 1건을 읽었습니다. 과거 기록을 추가한 뒤에는 두 쿼리 모두 `uk_todos_user_external`로 사용자 투두 10,003건을 읽고 날짜 등을 필터링하는 계획이 선택됐습니다. 인덱스 사용 여부만으로 충분히 최적화됐다고 판단할 수 없는 사례입니다.

후보 적용 후에는 오늘 조회가 사용자·삭제 여부·예정일 모두로 index lookup을 수행했고 별도 sort 노드가 사라졌습니다. 보상 합계는 사용자·완료 상태·완료 시각 구간으로 index range scan을 수행했습니다. `FORCE INDEX` 힌트는 사용하지 않았습니다.

## 프로브 SQL

실제 repository 조건을 동일한 값으로 바인딩한 SELECT 형태입니다. 오늘 조회는 entity 전체 조회에 대응하는 `t.*`를 사용했습니다. DB 세션 시간대는 UTC로 설정했으며 완료 시각 범위는 KST 날짜 2026-09-10에 대응합니다.

```sql
SELECT t.*
FROM todos t
WHERE t.user_id = 900000000
  AND t.deleted_at IS NULL
  AND (NULL IS NULL OR t.category_id = NULL)
  AND (NULL IS NULL OR t.status = NULL)
  AND ('2026-09-10' IS NULL OR t.due_date = '2026-09-10')
ORDER BY t.due_date, t.id;

SELECT COALESCE(SUM(t.reward_amount), 0)
FROM todos t
WHERE t.user_id = 900000000
  AND t.status = 'COMPLETED'
  AND t.completed_at >= '2026-09-09 15:00:00'
  AND t.completed_at <  '2026-09-10 15:00:00';

-- 격리 프로브 DB에만 생성했고 DB와 함께 삭제했습니다.
CREATE INDEX probe_todos_user_deleted_due
    ON todos(user_id, deleted_at, due_date);
CREATE INDEX probe_todos_user_status_completed
    ON todos(user_id, status, completed_at);
```

원본 3건 → 과거 10,000건 추가 → 후보 인덱스 추가의 각 단계에서 `ANALYZE TABLE todos`로 통계를 갱신하고, `EXPLAIN` 1회 및 `EXPLAIN ANALYZE` 3회를 수집했습니다. 각 단계의 결과 건수와 보상 합계가 같음을 검증했습니다. API는 스키마 생성 후 정지했고, DB는 2 CPU / 2GiB / buffer pool 1GiB였습니다. 동시 HTTP 부하나 쓰기 경쟁은 없었습니다.

## 후속 판단

사용자별 과거 투두·완료 이력을 포함한 데이터로 혼합 부하를 반복하고, 후보 인덱스 전후의 완료 쓰기 비용·보상 정합성·지연·처리량을 함께 비교할 근거가 생겼습니다. 새 인덱스는 쓰기 시 유지 비용이 들며, 기존 `idx_todos_user_status`와 선두 컬럼이 겹치는 후보는 다른 쿼리까지 확인한 후 유지 또는 교체를 결정해야 합니다. 이번 프로브에서는 기존 인덱스를 삭제하지 않았습니다. [MySQL 인덱스 문서](https://dev.mysql.com/doc/refman/8.4/en/optimization-indexes.html), [복합 인덱스 문서](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html).

[EXPLAIN ANALYZE 문서](https://dev.mysql.com/doc/refman/8.4/en/explain.html)와 [실제 계획 및 3회 원시 측정 JSON](2026-09-10-todo-index-probe.json)을 함께 확인할 수 있습니다. 프로브 run ID는 `20260910T115214-bb7abe-index-probe`입니다. 소유한 컨테이너·볼륨·네트워크는 종료 시 정리했습니다.
