package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class OnboardingDefaultHouseRollbackTest {

    @Autowired private OnboardingCommandService onboardingCommandService;
    @Autowired private UserRepository userRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private HouseGoalRepository houseGoalRepository;

    private Long userId;
    private Long goalId;
    private Long characterId;

    @AfterEach
    void cleanUp() {
        reset(houseGoalRepository);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM house_goals WHERE house_id IN "
                    + "(SELECT id FROM house WHERE owner_user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM house_members WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house WHERE owner_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_goals WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_characters WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (characterId != null) {
            jdbcTemplate.update("DELETE FROM characters WHERE id = ?", characterId);
        }
        if (goalId != null) {
            jdbcTemplate.update("DELETE FROM goals WHERE id = ?", goalId);
        }
    }

    @Test
    void 기본_집_생성이_실패하면_완료를_만든_캐릭터_선택도_롤백된다() {
        userId = userRepository.save(User.signUp()).getId();
        goalId = goalRepository.save(goal()).getId();
        characterId = characterRepository.save(
                new Character("rollback", "롤백", "characters/rollback.png", 1, true)).getId();
        onboardingCommandService.replaceGoals(
                userId, new OnboardingGoalsRequest(List.of(goalId), goalId));
        doThrow(new RuntimeException("기본 집 목표 저장 실패"))
                .when(houseGoalRepository).saveAll(anyList());

        assertThatThrownBy(() -> onboardingCommandService.selectCharacter(userId, characterId))
                .isInstanceOf(RuntimeException.class);

        assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(userId)).isEmpty();
        assertThat(houseRepository.findAll())
                .noneMatch(house -> house.getOwner().getId().equals(userId));
        assertThat(houseMemberRepository.findAll())
                .noneMatch(member -> member.getUser().getId().equals(userId));
    }

    private Goal goal() {
        Goal goal = BeanUtils.instantiateClass(Goal.class);
        ReflectionTestUtils.setField(goal, "code", "rollback-goal");
        ReflectionTestUtils.setField(goal, "name", "롤백 목표");
        ReflectionTestUtils.setField(goal, "sortOrder", 1);
        ReflectionTestUtils.setField(goal, "active", true);
        return goal;
    }
}
