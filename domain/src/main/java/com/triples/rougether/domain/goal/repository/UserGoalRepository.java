package com.triples.rougether.domain.goal.repository;

import com.triples.rougether.domain.goal.entity.UserGoal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserGoalRepository extends JpaRepository<UserGoal, Long> {

    List<UserGoal> findByUserId(Long userId);

    @Query("select ug from UserGoal ug join fetch ug.goal g where ug.user.id = :userId order by g.sortOrder asc")
    List<UserGoal> findByUserIdWithGoalOrderBySortOrder(@Param("userId") Long userId);

    void deleteByUserId(Long userId);
}
