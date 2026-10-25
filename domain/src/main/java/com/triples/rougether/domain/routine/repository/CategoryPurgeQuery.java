package com.triples.rougether.domain.routine.repository;

final class CategoryPurgeQuery {

    // RoutineLog 별칭 l과 categoryId, today 파라미터를 사용함. 기록과 사진의 삭제 대상을 일치시킴.
    static final String LOG_CONDITION = "l.routine.id in "
            + "(select r.id from Routine r where r.category.id = :categoryId "
            + "  and not exists (select 1 from Routine live "
            + "    where coalesce(live.originRoutineId, live.id) = coalesce(r.originRoutineId, r.id) "
            + "    and live.deletedAt is null)) "
            + "and not (l.routineDate = :today and l.rewardAmount > 0)";

    private CategoryPurgeQuery() {
    }
}
