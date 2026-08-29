package com.triples.rougether.adminapi.retention.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// day-end(FAILED 확정) 배치가 마지막으로 확정한 routine_date 조회.
// 정본은 Spring Batch 메타데이터(DayEndCatchUpPlanner 와 같은 소스)라 공유 DB 의 BATCH_* 테이블을 직접 읽는다
// — admin 은 batch 모듈에 의존하지 않고 job 이름·파라미터 키 문자열만 계약으로 공유한다(RoutineDayEndJobConfig).
// COMPLETED 실행이 하나라도 있으면 그 날짜의 FAILED 기록은 이미 커밋된 것이므로 max(COMPLETED targetDate)가 확정 상한이다.
// BATCH_* 테이블이 없는 환경(배치 미기동 로컬·테스트)이나 조회 실패는 empty — 호출자는 "확정 정보 없음"으로 다룬다.
@Slf4j
@Component
@RequiredArgsConstructor
public class DayEndFinalizationReader {

    static final String DAY_END_JOB_NAME = "routineDayEndJob";
    static final String TARGET_DATE_PARAM = "targetDate";

    private static final String LAST_FINALIZED_SQL = """
            SELECT MAX(p.PARAMETER_VALUE)
            FROM BATCH_JOB_EXECUTION e
            JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
            JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
            WHERE i.JOB_NAME = ? AND e.STATUS = 'COMPLETED' AND p.PARAMETER_NAME = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public Optional<LocalDate> findLastFinalizedDate() {
        try {
            String value = jdbcTemplate.queryForObject(
                    LAST_FINALIZED_SQL, String.class, DAY_END_JOB_NAME, TARGET_DATE_PARAM);
            return value == null ? Optional.empty() : Optional.of(LocalDate.parse(value));
        } catch (DataAccessException e) {
            log.warn("day-end 확정일 조회 실패 - BATCH_* 메타데이터가 없는 환경으로 보고 미확정으로 처리합니다.", e);
            return Optional.empty();
        } catch (DateTimeParseException e) {
            log.warn("day-end 확정일 파싱 실패 - 미확정으로 처리합니다.", e);
            return Optional.empty();
        }
    }
}
