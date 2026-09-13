package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.LocalDate;

// 오늘·캘린더가 그날 완료(COMPLETED)와 건너뜀(SKIPPED, mobile #189)을 한 번에 읽는 projection.
// 완료는 버전 id로, 건너뜀은 계보 키(coalesce(originRoutineId, id))로 대조한다 — 건너뜀 뒤 버전이 분기돼도 숨김이 유지되게.
public interface RoutineDayLogRow {

    Long getRoutineId();

    Long getLineageKey();

    LocalDate getRoutineDate();

    RoutineLogStatus getStatus();
}
