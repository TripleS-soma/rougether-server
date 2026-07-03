package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class OnboardingGoalReplaceRollbackTest {

    @Autowired
    private OnboardingGoalService service;
    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private UserGoalRepository userGoalRepository;

    private Long userId;
    private List<Long> goalIds;

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            userGoalRepository.deleteAll(userGoalRepository.findByUserId(userId));
            userRepository.deleteById(userId);
        }
        if (goalIds != null) {
            goalRepository.deleteAllById(goalIds);
        }
    }

    @Test
    void 저장_단계_실패시_기존_선택이_전부_유지된다() {
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        Long g1 = goalRepository.save(goal("g1")).getId();
        Long g2 = goalRepository.save(goal("g2")).getId();
        Long g3 = goalRepository.save(goal("g3")).getId();
        goalIds = List.of(g1, g2, g3);

        service.replaceGoals(userId, new OnboardingGoalsRequest(List.of(g1, g2), g1));

        doThrow(new RuntimeException("저장 실패")).when(userGoalRepository).saveAll(anyList());

        assertThatThrownBy(() -> service.replaceGoals(userId, new OnboardingGoalsRequest(List.of(g3), g3)))
                .isInstanceOf(RuntimeException.class);

        List<UserGoal> preserved = userGoalRepository.findByUserId(userId);
        assertThat(preserved).extracting(ug -> ug.getGoal().getId()).containsExactlyInAnyOrder(g1, g2);
        assertThat(preserved).filteredOn(UserGoal::isPrimary)
                .extracting(ug -> ug.getGoal().getId()).containsExactly(g1);
    }

    private Goal goal(String code) {
        Goal g = BeanUtils.instantiateClass(Goal.class);
        ReflectionTestUtils.setField(g, "code", code);
        ReflectionTestUtils.setField(g, "name", code + "-name");
        ReflectionTestUtils.setField(g, "sortOrder", 0);
        ReflectionTestUtils.setField(g, "active", true);
        return g;
    }
}
