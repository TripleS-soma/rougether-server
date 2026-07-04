package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsResponse.GoalSelection;
import com.triples.rougether.userapi.onboarding.dto.OnboardingResponse;
import com.triples.rougether.userapi.onboarding.dto.OnboardingSummary;
import java.time.Instant;
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
class OnboardingQueryServiceIntegrationTest {

    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private UserGoalRepository userGoalRepository;
    @Autowired
    private CharacterRepository characterRepository;
    @Autowired
    private UserCharacterRepository userCharacterRepository;
    @Autowired
    private UserRepository userRepository;

    private OnboardingQueryService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new OnboardingQueryService(
                userGoalRepository, userCharacterRepository, goalRepository, characterRepository);
        user = userRepository.save(User.signUp());
    }

    @Test
    void 아무것도_없으면_미완료이고_전부_null() {
        OnboardingResponse response = service.getOnboarding(user.getId());

        assertThat(response.goals()).isEmpty();
        assertThat(response.primaryGoalId()).isNull();
        assertThat(response.selectedCharacterId()).isNull();
        assertThat(response.completed()).isFalse();
    }

    @Test
    void 목표만_있으면_미완료() {
        Goal g = goalRepository.save(goal("g1", 0));
        userGoalRepository.save(UserGoal.of(user, g, true));

        OnboardingResponse response = service.getOnboarding(user.getId());

        assertThat(response.goals()).extracting(GoalSelection::goalId).containsExactly(g.getId());
        assertThat(response.primaryGoalId()).isEqualTo(g.getId());
        assertThat(response.selectedCharacterId()).isNull();
        assertThat(response.completed()).isFalse();
    }

    @Test
    void 캐릭터만_있으면_미완료() {
        Character c = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 0, true));
        userCharacterRepository.save(UserCharacter.of(user, c, Instant.now(), true));

        OnboardingResponse response = service.getOnboarding(user.getId());

        assertThat(response.goals()).isEmpty();
        assertThat(response.primaryGoalId()).isNull();
        assertThat(response.selectedCharacterId()).isEqualTo(c.getId());
        assertThat(response.completed()).isFalse();
    }

    @Test
    void 목표와_캐릭터가_모두_있으면_완료() {
        Goal g = goalRepository.save(goal("g1", 0));
        userGoalRepository.save(UserGoal.of(user, g, false));
        Character c = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 0, true));
        userCharacterRepository.save(UserCharacter.of(user, c, Instant.now(), true));

        OnboardingResponse response = service.getOnboarding(user.getId());

        assertThat(response.completed()).isTrue();
        assertThat(response.primaryGoalId()).isNull();
        assertThat(response.selectedCharacterId()).isEqualTo(c.getId());
    }

    @Test
    void 목표는_마스터_sortOrder_오름차순으로_준다() {
        Goal later = goalRepository.save(goal("later", 2));
        Goal earlier = goalRepository.save(goal("earlier", 1));
        userGoalRepository.save(UserGoal.of(user, later, false));
        userGoalRepository.save(UserGoal.of(user, earlier, false));

        OnboardingResponse response = service.getOnboarding(user.getId());

        assertThat(response.goals()).extracting(GoalSelection::code).containsExactly("earlier", "later");
    }

    @Test
    void 요약은_상세와_동일한_완료_대표_선택값을_준다() {
        Goal g = goalRepository.save(goal("g1", 0));
        userGoalRepository.save(UserGoal.of(user, g, true));
        Character c = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 0, true));
        userCharacterRepository.save(UserCharacter.of(user, c, Instant.now(), true));

        OnboardingSummary summary = service.getSummary(user.getId());

        assertThat(summary.completed()).isTrue();
        assertThat(summary.primaryGoalId()).isEqualTo(g.getId());
        assertThat(summary.selectedCharacterId()).isEqualTo(c.getId());
    }

    private Goal goal(String code, int sortOrder) {
        Goal g = BeanUtils.instantiateClass(Goal.class);
        ReflectionTestUtils.setField(g, "code", code);
        ReflectionTestUtils.setField(g, "name", code + "-name");
        ReflectionTestUtils.setField(g, "sortOrder", sortOrder);
        ReflectionTestUtils.setField(g, "active", true);
        return g;
    }
}
