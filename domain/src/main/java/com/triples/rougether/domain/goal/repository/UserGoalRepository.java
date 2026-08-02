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

    // 온보딩 목표 재선택(replace) 경로용 파생 삭제 — 엔티티를 로드해 영속성 컨텍스트를 경유함.
    void deleteByUserId(Long userId);

    // 회원탈퇴 전용 bulk 삭제 — PC 를 거치지 않는 단일 DELETE. 위 파생 삭제와 혼용하지 말 것.
    @Modifying(flushAutomatically = true)
    @Query("delete from UserGoal ug where ug.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
