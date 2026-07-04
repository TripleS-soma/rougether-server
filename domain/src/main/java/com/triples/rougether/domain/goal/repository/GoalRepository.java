package com.triples.rougether.domain.goal.repository;

import com.triples.rougether.domain.goal.entity.Goal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    // 집 생성 시 goalIds 검증용 - 활성 goal 만 유효.
    List<Goal> findByIdInAndActiveIsTrue(Collection<Long> ids);
}
