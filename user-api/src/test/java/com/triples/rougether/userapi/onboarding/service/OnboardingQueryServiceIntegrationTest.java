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
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @Autowired
    private EntityManager em;

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

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,true", "false,true,false", "true,true,false", "true,true,true"})
    void 요약_projection은_모든_완료조합에서_상세와_같고_엔티티를_로드하지_않는다(
            boolean hasGoal, boolean hasCharacter, boolean primary) {
        if (hasGoal) userGoalRepository.save(UserGoal.of(user, goalRepository.save(goal("g1", 0)), primary));
        if (hasCharacter) {
            Character c = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 0, true));
            userCharacterRepository.save(UserCharacter.of(user, c, Instant.now(), true));
        }
        OnboardingResponse detail = service.getOnboarding(user.getId());
        Long userId = user.getId();
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThat(service.getSummary(userId)).isEqualTo(new OnboardingSummary(
                    detail.completed(), detail.primaryGoalId(), detail.selectedCharacterId()));
            assertThat(statistics.getEntityLoadCount()).isZero();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void 요약도_목표_정렬순으로_대표를_선택하고_삭제캐릭터와_타인을_제외한다() {
        Goal later = goalRepository.save(goal("later", 2));
        Goal earlier = goalRepository.save(goal("earlier", 1));
        userGoalRepository.save(UserGoal.of(user, later, true));
        userGoalRepository.save(UserGoal.of(user, earlier, true));
        Character c = characterRepository.save(new Character("c1", "고양이", "characters/c1.png", 0, true));
        UserCharacter removed = userCharacterRepository.save(UserCharacter.of(user, c, Instant.now(), true));
        ReflectionTestUtils.setField(removed, "deletedAt", Instant.now());
        User other = userRepository.save(User.signUp());
        userCharacterRepository.save(UserCharacter.of(other, c, Instant.now(), true));
        userGoalRepository.save(UserGoal.of(other, later, true));
        em.flush();
        em.clear();

        OnboardingSummary summary = service.getSummary(user.getId());
        assertThat(summary).isEqualTo(new OnboardingSummary(false, earlier.getId(), null));
        OnboardingResponse detail = service.getOnboarding(user.getId());
        assertThat(summary).isEqualTo(new OnboardingSummary(
                detail.completed(), detail.primaryGoalId(), detail.selectedCharacterId()));
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
