package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse.GoalSelection;
import com.triples.rougether.userapi.onboarding.dto.OnboardingResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingSummary;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingQueryService {

    private final UserGoalRepository userGoalRepository;
    private final UserCharacterRepository userCharacterRepository;

    public OnboardingQueryService(UserGoalRepository userGoalRepository,
                                  UserCharacterRepository userCharacterRepository) {
        this.userGoalRepository = userGoalRepository;
        this.userCharacterRepository = userCharacterRepository;
    }

    @Transactional(readOnly = true)
    public OnboardingResponse getOnboarding(Long userId) {
        return compute(userId);
    }

    @Transactional(readOnly = true)
    public OnboardingSummary getSummary(Long userId) {
        OnboardingResponse onboarding = compute(userId);
        return new OnboardingSummary(
                onboarding.completed(), onboarding.primaryGoalId(), onboarding.selectedCharacterId());
    }

    private OnboardingResponse compute(Long userId) {
        List<UserGoal> userGoals = userGoalRepository.findByUserIdWithGoalOrderBySortOrder(userId);

        List<GoalSelection> goals = userGoals.stream().map(GoalSelection::of).toList();
        Long primaryGoalId = userGoals.stream()
                .filter(UserGoal::isPrimary)
                .map(ug -> ug.getGoal().getId())
                .findFirst()
                .orElse(null);
        Long selectedCharacterId = userCharacterRepository
                .findByUserIdAndSelectedTrueAndDeletedAtIsNull(userId)
                .map(uc -> uc.getCharacter().getId())
                .orElse(null);
        boolean completed = !userGoals.isEmpty() && selectedCharacterId != null;

        return new OnboardingResponse(goals, primaryGoalId, selectedCharacterId, completed);
    }
}
