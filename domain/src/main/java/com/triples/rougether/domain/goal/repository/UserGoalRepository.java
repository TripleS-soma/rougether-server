package com.triples.rougether.domain.goal.repository;

import com.triples.rougether.domain.goal.entity.UserGoal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserGoalRepository extends JpaRepository<UserGoal, Long> {

    List<UserGoal> findByUserId(Long userId);

    @Query("select ug from UserGoal ug join fetch ug.goal g where ug.user.id = :userId order by g.sortOrder asc")
    List<UserGoal> findByUserIdWithGoalOrderBySortOrder(@Param("userId") Long userId);

    void deleteByUserId(Long userId);

    // 회원탈퇴 시 온보딩 목표 전량 삭제 — 본인 전용 데이터라 즉시 파기함.
    @Modifying(flushAutomatically = true)
    @Query("delete from UserGoal ug where ug.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
