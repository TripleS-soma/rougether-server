#!/usr/bin/env bash
# 빈 MySQL에서 첫 요청을 재현한다. CHECK가 실패해도 결과를 출력하고 환경을 지운다.
set -euo pipefail
cd "$(dirname "$0")"
QA500_JAR=${QA500_JAR:-"$(cd ../.. && pwd)/user-api/build/libs/user-api-0.0.1-SNAPSHOT.jar"}
export QA500_JAR
[[ -s "$QA500_JAR" ]] || { echo '먼저 ./gradlew :user-api:bootJar 를 실행하세요.' >&2; exit 2; }
export QA500_DB_PASSWORD="$(openssl rand -hex 16)"
export QA500_JWT_SECRET="$(openssl rand -hex 32)"
compose=(docker compose -p "qa500-run-$$" -f compose.yml)
db() { "${compose[@]}" exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N qa500'; }
cleanup() { "${compose[@]}" down -v >/dev/null; }
trap cleanup EXIT
"${compose[@]}" up -d
ready=0
for ((i=0; i<100; i++)); do
  if "${compose[@]}" exec -T runner python -c "import urllib.request; urllib.request.urlopen('http://api:8080/api/v1/health',timeout=3)" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 3
done
if (( !ready )); then "${compose[@]}" logs --tail 30 api; exit 2; fi
"${compose[@]}" exec -T runner python /qa/probe.py seed | db
result=0
"${compose[@]}" exec -T runner python /qa/probe.py || result=1
db <tps.sql | "${compose[@]}" exec -T runner python /qa/probe.py tps
rows=$(db <check.sql)
printf '일반 요청과 초대자 한도 검증 결과\n%s\n' "$rows"
[[ "$rows" == $'73\t146\t146\t73\t73\n12\t500\t600\t600' ]] || result=1
deadlocks=$(db <<< "SELECT count FROM information_schema.innodb_metrics WHERE name = 'lock_deadlocks'")
printf 'MySQL 교착 %s건\n' "$deadlocks"
[[ "$deadlocks" == 0 ]] || result=1
exit "$result"
