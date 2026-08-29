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
