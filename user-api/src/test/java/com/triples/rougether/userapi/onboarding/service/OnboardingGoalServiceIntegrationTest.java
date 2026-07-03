package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class OnboardingGoalServiceIntegrationTest {

    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private UserGoalRepository userGoalRepository;
    @Autowired
    private UserRepository userRepository;

    private OnboardingGoalService service;
    private Long userId;
    private Long g1;
    private Long g2;
    private Long g3;
    private Long inactive;

    @BeforeEach
    void setUp() {
        service = new OnboardingGoalService(goalRepository, userGoalRepository, userRepository);
        userId = userRepository.save(User.signUp()).getId();
        g1 = goalRepository.save(goal("g1", true)).getId();
        g2 = goalRepository.save(goal("g2", true)).getId();
        g3 = goalRepository.save(goal("g3", true)).getId();
        inactive = goalRepository.save(goal("gx", false)).getId();
    }

    @Test
    void 신규_저장은_선택_목표와_대표를_저장한다() {
        OnboardingGoalsResponse response = service.replaceGoals(userId,
                new OnboardingGoalsRequest(List.of(g1, g2), g1));

        assertThat(response.goals()).extracting(OnboardingGoalsResponse.GoalSelection::goalId)
                .containsExactly(g1, g2);
        assertThat(response.primaryGoalId()).isEqualTo(g1);

        List<UserGoal> saved = userGoalRepository.findByUserId(userId);
        assertThat(saved).hasSize(2);
        assertThat(saved).filteredOn(UserGoal::isPrimary)
                .extracting(ug -> ug.getGoal().getId()).containsExactly(g1);
    }

    @Test
    void 재호출시_기존_선택을_전체_교체한다() {
        service.replaceGoals(userId, new OnboardingGoalsRequest(List.of(g1, g2), g1));

        service.replaceGoals(userId, new OnboardingGoalsRequest(List.of(g2, g3), g3));

        List<UserGoal> saved = userGoalRepository.findByUserId(userId);
        assertThat(saved).extracting(ug -> ug.getGoal().getId()).containsExactlyInAnyOrder(g2, g3);
        assertThat(saved).filteredOn(UserGoal::isPrimary)
                .extracting(ug -> ug.getGoal().getId()).containsExactly(g3);
    }

    @Test
    void 대표_생략시_대표가_없다() {
        OnboardingGoalsResponse response = service.replaceGoals(userId,
                new OnboardingGoalsRequest(List.of(g1), null));

        assertThat(response.primaryGoalId()).isNull();
        assertThat(userGoalRepository.findByUserId(userId)).noneMatch(UserGoal::isPrimary);
    }

    @Test
    void 중복_goalId는_dedupe된다() {
        service.replaceGoals(userId, new OnboardingGoalsRequest(List.of(g1, g1, g2), null));

        assertThat(userGoalRepository.findByUserId(userId)).hasSize(2);
    }

    @Test
    void 빈_목표는_GOAL_REQUIRED() {
        assertBusiness(() -> service.replaceGoals(userId, new OnboardingGoalsRequest(List.of(), null)),
                MemberErrorCode.GOAL_REQUIRED);
    }

    @Test
    void 비존재_goalId는_INVALID_GOAL() {
        assertBusiness(() -> service.replaceGoals(userId,
                        new OnboardingGoalsRequest(List.of(g1, 999_999L), null)),
                MemberErrorCode.INVALID_GOAL);
    }

    @Test
    void 비활성_goalId는_INVALID_GOAL() {
        assertBusiness(() -> service.replaceGoals(userId,
                        new OnboardingGoalsRequest(List.of(g1, inactive), null)),
                MemberErrorCode.INVALID_GOAL);
    }

    @Test
    void 대표가_선택에_없으면_PRIMARY_GOAL_NOT_IN_SELECTION() {
        assertBusiness(() -> service.replaceGoals(userId,
                        new OnboardingGoalsRequest(List.of(g1), g2)),
                MemberErrorCode.PRIMARY_GOAL_NOT_IN_SELECTION);
    }

    private void assertBusiness(Runnable runnable, MemberErrorCode expected) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private Goal goal(String code, boolean active) {
        Goal g = BeanUtils.instantiateClass(Goal.class);
        ReflectionTestUtils.setField(g, "code", code);
        ReflectionTestUtils.setField(g, "name", code + "-name");
        ReflectionTestUtils.setField(g, "sortOrder", 0);
        ReflectionTestUtils.setField(g, "active", active);
        return g;
    }
}
