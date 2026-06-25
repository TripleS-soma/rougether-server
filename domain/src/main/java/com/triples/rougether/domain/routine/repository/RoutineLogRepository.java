package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.RoutineLog;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineLogRepository extends JpaRepository<RoutineLog, Long> {

    List<RoutineLog> findByRoutineIdAndRoutineDate(Long routineId, LocalDate routineDate);

    Optional<RoutineLog> findByRoutineIdAndRoutineDateAndStatus(Long routineId, LocalDate routineDate, String status);
}
