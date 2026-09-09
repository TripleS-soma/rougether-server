package com.triples.rougether.domain.routine.repository;

import java.time.LocalDate;

// 월 캘린더의 오늘·미래 대상 루틴과 완료 기록을 교차 확인하는 projection임
public interface RoutineCompletionDate {

    Long getRoutineId();

    LocalDate getRoutineDate();
}
