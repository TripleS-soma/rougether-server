package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingCharacterResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingCommandService {

    private final GoalRepository goalRepository;
    private final UserGoalRepository userGoalRepository;
    private final CharacterRepository characterRepository;
    private final UserCharacterRepository userCharacterRepository;
    private final UserRepository userRepository;
    private final HouseCommandService houseCommandService;

    public OnboardingGoalsResponse replaceGoals(Long userId, OnboardingGoalsRequest request) {
        if (request.goalIds() == null || request.goalIds().isEmpty()) {
            throw new BusinessException(MemberErrorCode.GOAL_REQUIRED);
        }

        // 같은 유저의 목표 교체·기본 집 목표 채움을 유저 행 락으로 직렬화(재시도·동시 호출에도 집 목표는 1회만 채움)
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));

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

        List<UserGoal> saved = goalIds.stream()
                .map(id -> UserGoal.of(user, activeById.get(id), id.equals(primaryGoalId)))
                .toList();
        userGoalRepository.saveAll(saved);

        List<UserGoal> ordered = saved.stream()
                .sorted(Comparator.comparingInt(ug -> ug.getGoal().getSortOrder()))
                .toList();
        // 가입 때 만들어진 기본 집(#322)은 목표가 비어 있다 — 첫 목표 저장에서 대표 우선·정렬순 최대 3개를 채운다
        houseCommandService.fillStarterHouseGoals(userId, starterHouseGoals(ordered));
        return OnboardingGoalsResponse.of(ordered);
    }

    public OnboardingCharacterResponse selectCharacter(Long userId, Long characterId) {
        // 대표 캐릭터 유일성(is_selected) 보장을 위해 유저 행 락으로 동시 선택을 직렬화
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));

        Character character = characterRepository.findById(characterId)
                .filter(Character::isActive)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.CHARACTER_NOT_FOUND));

        Optional<UserCharacter> owned =
                userCharacterRepository.findByUserIdAndCharacterIdAndDeletedAtIsNull(userId, characterId);
        if (owned.isPresent()) {
            UserCharacter target = owned.get();
            if (!target.isSelected()) {
                userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(userId)
                        .ifPresent(UserCharacter::unselect);
                target.select();
                userCharacterRepository.save(target);
            }
        } else if (!userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId).isEmpty()) {
            throw new BusinessException(MemberErrorCode.CHARACTER_NOT_OWNED);
        } else {
            userCharacterRepository.save(UserCharacter.createSelected(user, character));
        }

        return new OnboardingCharacterResponse(characterId);
    }

    private List<Goal> starterHouseGoals(List<UserGoal> userGoals) {
        return userGoals.stream()
                .sorted(Comparator.comparing(UserGoal::isPrimary).reversed()
                        .thenComparingInt(userGoal -> userGoal.getGoal().getSortOrder()))
                .limit(3)
                .map(UserGoal::getGoal)
                .toList();
    }
}
