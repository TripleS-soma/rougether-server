package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.userapi.onboarding.dto.GoalListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoalQueryService {

    private final GoalRepository goalRepository;

    public GoalQueryService(GoalRepository goalRepository) {
        this.goalRepository = goalRepository;
    }

    @Transactional(readOnly = true)
    public GoalListResponse getGoals() {
        return GoalListResponse.of(goalRepository.findByActiveTrueOrderBySortOrderAsc());
    }
}
