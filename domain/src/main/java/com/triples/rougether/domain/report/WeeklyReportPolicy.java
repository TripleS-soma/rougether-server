package com.triples.rougether.domain.report;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

// 주간 회고 결정값의 정본. batch(생성)와 admin-api(관측)가 같은 값을 참조해 조용한 드리프트를 막는다.
public final class WeeklyReportPolicy {

    // 회고 집계에 세는 루틴 log 상태. 이 상태의 log가 그 주에 하나라도 있는 사용자가 생성 대상이다.
    public static final List<RoutineLogStatus> COUNTED_LOG_STATUSES =
            List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.FAILED);

    // 주 = 일~토(KST). weekEnd = weekStart + 6.
    public static final int WEEK_LENGTH_DAYS = 7;

    // batch 는 매시 30분에 가장 최근 끝난 주를 처리하므로, 주가 끝난 뒤 첫 실행 시각은 다음 일요일 00:30 KST.
    public static final LocalTime FIRST_TRIGGER_TIME = LocalTime.of(0, 30);

    // push 발송 시각(KST). 새벽 생성(FIRST_TRIGGER_TIME, 일 00:30)과 분리한 발송 시각 - 회고는 자는 새벽에 만들어 두고,
    // 알림은 사용자가 깨어서 다음 주를 계획하는 일요일 저녁에 보낸다.
    public static final LocalTime PUSH_TIME = LocalTime.of(20, 0);

    private WeeklyReportPolicy() {
    }

    // 오늘 기준 가장 최근에 끝난 일~토 주의 일요일. 일요일 당일이면 7일 전 일요일(오늘은 다음 주 소속).
    public static LocalDate latestCompletedWeekStart(LocalDate today) {
        LocalDate thisWeekSunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        return thisWeekSunday.minusDays(WEEK_LENGTH_DAYS);
    }

    public static LocalDate weekEndOf(LocalDate weekStart) {
        return weekStart.plusDays(WEEK_LENGTH_DAYS - 1L);
    }

    // weekStart 주(일~토)가 끝난 뒤 처음 오는 일요일(= weekStart + 7일)의 PUSH_TIME. 이 시각(KST) 이후에만 push 한다.
    public static LocalDateTime pushReadyAt(LocalDate weekStart) {
        return weekStart.plusDays(WEEK_LENGTH_DAYS).atTime(PUSH_TIME);
    }
}
