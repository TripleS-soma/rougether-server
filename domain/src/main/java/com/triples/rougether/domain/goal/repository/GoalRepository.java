package com.triples.rougether.domain.goal.repository;

import com.triples.rougether.domain.goal.entity.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, Long> {
}
