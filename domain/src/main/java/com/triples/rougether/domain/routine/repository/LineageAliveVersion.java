package com.triples.rougether.domain.routine.repository;

// 관리자 추천 퍼널(#332)용 계보별 살아있는 버전 projection. 계보당 살아있는 row 최대 1개 가정이지만
// (findAliveByLineage 주석 참조), race 위반이 있어도 집계가 죽지 않게 다건을 허용해 담는다.
public interface LineageAliveVersion {

    Long getOriginKey();

    Long getRoutineId();
}
