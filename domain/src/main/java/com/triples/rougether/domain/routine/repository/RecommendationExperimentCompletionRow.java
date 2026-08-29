package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.LocalDate;

public interface RecommendationExperimentCompletionRow {

    Long getUserId();

    LocalDate getRoutineDate();

    RoutineLogStatus getStatus();
}
