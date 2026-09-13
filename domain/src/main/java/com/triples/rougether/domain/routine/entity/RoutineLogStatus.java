package com.triples.rougether.domain.routine.entity;

public enum RoutineLogStatus {
    PENDING,    // 미수행
    COMPLETED,  // 완료
    FAILED,     // 실패
    SKIPPED     // 그날 발생분 건너뜀 — 사용자가 다른 날로 옮김 (mobile #189). 보상·스트릭·집계 대상 아님
}
