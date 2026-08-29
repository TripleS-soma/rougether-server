package com.triples.rougether.domain.recommendation.repository;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentEligibility;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationExperimentEligibilityRepository
        extends JpaRepository<RecommendationExperimentEligibility, Long> {

    boolean existsByAssignmentIdAndCohortWeekStart(Long assignmentId, LocalDate cohortWeekStart);

    // 관리자 HOLDOUT 관측(#342): 현재 살아있는 실제 사용자의 최초 적격 cohort만 효과 비교 분모에 포함한다.
    // 후속 주 적격 기록은 운영 감사용으로 남기되, 이미 실험에 노출된 사용자를 새 cohort로 다시 세지 않는다.
    //
    // min-cohort 서브쿼리는 팔 간 대칭의 전제이기도 하다 - CONTROL 은 추천이 없어 상한·쿨다운에 안 걸려
    // 매주 적격이 쌓이고 TREATMENT 는 격주로만 쌓이므로, 이 서브쿼리를 제거하면 즉시 비대칭 오염이 된다.
    // deletedAt 필터는 조회 시점 기준이라 코호트 확정 후 탈퇴자는 과거 주차 분모에서도 소급 제외된다
    // (purge 가 배정 행을 지우는 것과 함께 생존자 편향 관측 한계 - routine-todo.md 참고).
    @Query("select a.user.id as userId, e.cohortWeekStart as cohortWeekStart, a.variant as variant "
            + "from RecommendationExperimentEligibility e join e.assignment a join a.user u "
            + "where a.experimentKey = :experimentKey "
            + "and e.cohortWeekStart between :fromWeekStart and :toWeekStart "
            + "and e.cohortWeekStart = (select min(first.cohortWeekStart) "
            + "  from RecommendationExperimentEligibility first where first.assignment = a) "
            + "and u.deletedAt is null and u.bot = false")
    List<RecommendationExperimentEligibilityRow> findActiveHumanRows(
            @Param("experimentKey") String experimentKey,
            @Param("fromWeekStart") LocalDate fromWeekStart,
            @Param("toWeekStart") LocalDate toWeekStart);
}
