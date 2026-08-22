package com.triples.rougether.batch.recommendation;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

// 조정 추천 결정값(#329). 수치는 spec 초안 기본값이며 운영 데이터로 조정한다(spec open-questions).
// 현재는 batch 만 쓰므로 여기 두고, admin 관측이 생기면 domain 으로 승격을 검토함(WeeklyReportPolicy 전례).
final class RecommendationPolicy {

    // 집계에 세는 log 상태(수행 대상이었던 기록 전부)
    static final List<RoutineLogStatus> COUNTED_LOG_STATUSES =
            List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.FAILED);

    // 근거 창: 직전 일~토 주(weekStart 주)를 포함한 최근 3주
    static final int WINDOW_WEEKS = 3;

    // 룰 1(실패 요일 제외): 제외 후보 요일 밖 나머지 요일의 최소 완료율
    static final double OTHER_DAYS_COMPLETION_MIN = 0.5;

    // 룰 2(빈도 축소): 직전 2주 각각의 주 완료율 상한(미만이면 후보), 대상 최소 요일 수(DAILY 는 항상 대상),
    // 전환 제안 요일 최대 개수, 제안 근거가 되는 완료 이력 요일 최소 개수
    static final double LOW_COMPLETION_MAX = 0.4;
    static final int REDUCE_TARGET_MIN_DAYS = 5;
    static final int REDUCE_PROPOSED_MAX_DAYS = 3;
    static final int REDUCE_MIN_COMPLETED_WEEKDAYS = 2;

    // 사용자당 활성(ACTIVE·미만료) 추천 상한 / 같은 계보 재추천 금지 기간(직전 생성일 기준, 상태 무관) / 추천 수명
    static final int ACTIVE_CAP_PER_USER = 3;
    static final Duration LINEAGE_COOLDOWN = Duration.ofDays(14);
    static final Duration TTL = Duration.ofDays(7);

    private RecommendationPolicy() {
    }

    // 근거 창 시작일: weekStart(대상 주 일요일)로부터 2주 전 일요일
    static LocalDate windowStart(LocalDate weekStart) {
        return weekStart.minusWeeks(WINDOW_WEEKS - 1L);
    }
}
