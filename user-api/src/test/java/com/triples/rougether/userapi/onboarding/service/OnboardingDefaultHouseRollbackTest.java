package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.userapi.auth.service.SignupService;
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
    @Autowired private UserGoalRepository userGoalRepository;
    @Autowired private SignupService signupService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private HouseGoalRepository houseGoalRepository;

    private Long userId;
    private Long goalId;

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
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (goalId != null) {
            jdbcTemplate.update("DELETE FROM goals WHERE id = ?", goalId);
        }
    }

    // #322: 집은 가입 때 이미 있으므로, 온보딩 목표 저장과 집 목표 채움이 한 트랜잭션임을 본다
    @Test
    void 기본_집_목표_채움이_실패하면_목표_저장도_롤백된다() {
        userId = signupService.register(null).getId();
        goalId = goalRepository.save(goal()).getId();
        House starter = houseMemberRepository.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE)
                .getFirst().getHouse();
        doThrow(new RuntimeException("기본 집 목표 저장 실패"))
                .when(houseGoalRepository).saveAll(anyList());

        assertThatThrownBy(() -> onboardingCommandService.replaceGoals(
                userId, new OnboardingGoalsRequest(List.of(goalId), goalId)))
                .isInstanceOf(RuntimeException.class);

        assertThat(userGoalRepository.existsByUserId(userId)).isFalse();
        assertThat(houseGoalRepository.findByHouseId(starter.getId())).isEmpty();
        // 집 자체는 가입 때 만들어진 그대로 남아 있음
        assertThat(houseRepository.findById(starter.getId())).isPresent();
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
