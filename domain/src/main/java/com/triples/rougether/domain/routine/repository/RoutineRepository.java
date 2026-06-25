package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Routine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineRepository extends JpaRepository<Routine, Long> {

    List<Routine> findByUserIdAndStatusAndDeletedAtIsNull(Long userId, String status);
}
