package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Streak;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreakRepository extends JpaRepository<Streak, Long> {

    Optional<Streak> findByUserId(Long userId);
}
