package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.onboarding.dto.CharacterListResponse;
import com.triples.rougether.userapi.onboarding.dto.GoalListResponse;
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
class OnboardingMasterQueryServiceIntegrationTest {

    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private CharacterRepository characterRepository;

    private GoalQueryService goalQueryService;
    private CharacterQueryService characterQueryService;

    @BeforeEach
    void setUp() {
        goalQueryService = new GoalQueryService(goalRepository);
        characterQueryService = new CharacterQueryService(characterRepository);
    }

    @Test
    void 목표는_활성만_sortOrder_오름차순으로_준다() {
        goalRepository.save(goal("g_b", "나중", 2, true));
        goalRepository.save(goal("g_a", "먼저", 1, true));
        goalRepository.save(goal("g_x", "비활성", 0, false));

        var items = goalQueryService.getGoals().items();

        assertThat(items).extracting(GoalListResponse.GoalItem::code).containsExactly("g_a", "g_b");
        assertThat(items).extracting(GoalListResponse.GoalItem::name).containsExactly("먼저", "나중");
    }

    @Test
    void 캐릭터는_활성만_sortOrder_오름차순으로_준다() {
        characterRepository.save(new Character("c_b", "나중", "characters/b.png", 2, true));
        characterRepository.save(new Character("c_a", "먼저", "characters/a.png", 1, true));
        characterRepository.save(new Character("c_x", "비활성", "characters/x.png", 0, false));

        var items = characterQueryService.getCharacters().items();

        assertThat(items).extracting(CharacterListResponse.CharacterItem::code).containsExactly("c_a", "c_b");
        assertThat(items).extracting(CharacterListResponse.CharacterItem::baseAssetKey)
                .containsExactly("characters/a.png", "characters/b.png");
    }

    @Test
    void 활성_목표가_없으면_빈_배열() {
        goalRepository.save(goal("g_x", "비활성", 0, false));

        assertThat(goalQueryService.getGoals().items()).isEmpty();
    }

    private Goal goal(String code, String name, int sortOrder, boolean active) {
        Goal g = BeanUtils.instantiateClass(Goal.class);
        ReflectionTestUtils.setField(g, "code", code);
        ReflectionTestUtils.setField(g, "name", name);
        ReflectionTestUtils.setField(g, "sortOrder", sortOrder);
        ReflectionTestUtils.setField(g, "active", active);
        return g;
    }
}
