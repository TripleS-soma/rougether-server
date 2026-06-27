package com.triples.rougether.domain.goal.repository;

import com.triples.rougether.domain.goal.entity.UserGoal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGoalRepository extends JpaRepository<UserGoal, Long> {

    List<UserGoal> findByUserId(Long userId);
}
