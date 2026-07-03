package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingGoalService {

    private final GoalRepository goalRepository;
    private final UserGoalRepository userGoalRepository;
    private final UserRepository userRepository;

    public OnboardingGoalService(GoalRepository goalRepository,
                                 UserGoalRepository userGoalRepository,
                                 UserRepository userRepository) {
        this.goalRepository = goalRepository;
        this.userGoalRepository = userGoalRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public OnboardingGoalsResponse replaceGoals(Long userId, OnboardingGoalsRequest request) {
        if (request.goalIds() == null || request.goalIds().isEmpty()) {
            throw new BusinessException(MemberErrorCode.GOAL_REQUIRED);
        }

        LinkedHashSet<Long> goalIds = new LinkedHashSet<>(request.goalIds());

        Map<Long, Goal> activeById = goalRepository.findAllById(goalIds).stream()
                .filter(Goal::isActive)
                .collect(Collectors.toMap(Goal::getId, Function.identity()));
        if (activeById.size() != goalIds.size()) {
            throw new BusinessException(MemberErrorCode.INVALID_GOAL);
        }

        Long primaryGoalId = request.primaryGoalId();
        if (primaryGoalId != null && !goalIds.contains(primaryGoalId)) {
            throw new BusinessException(MemberErrorCode.PRIMARY_GOAL_NOT_IN_SELECTION);
        }

        userGoalRepository.deleteByUserId(userId);
        userGoalRepository.flush();

        User user = userRepository.getReferenceById(userId);
        List<UserGoal> saved = goalIds.stream()
                .map(id -> UserGoal.of(user, activeById.get(id), id.equals(primaryGoalId)))
                .toList();
        userGoalRepository.saveAll(saved);

        List<UserGoal> ordered = saved.stream()
                .sorted(Comparator.comparingInt(ug -> ug.getGoal().getSortOrder()))
                .toList();
        return OnboardingGoalsResponse.of(ordered);
    }
}
