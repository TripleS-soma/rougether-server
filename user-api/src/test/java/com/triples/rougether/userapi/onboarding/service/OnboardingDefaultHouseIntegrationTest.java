package com.triples.rougether.userapi.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.onboarding.dto.OnboardingGoalsRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class OnboardingDefaultHouseIntegrationTest {

    private static final String DEFAULT_COVER_KEY =
            "house/cloud-balloon/house-unified-cloud-balloon-frame.png";

    @Autowired private OnboardingCommandService onboardingCommandService;
    @Autowired private UserRepository userRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseGoalRepository houseGoalRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> goalIds = new ArrayList<>();
    private final List<Long> characterIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM house_goals WHERE house_id IN "
                    + "(SELECT id FROM house WHERE owner_user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM house_members WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house WHERE owner_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_goals WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_characters WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        characterIds.forEach(id -> jdbcTemplate.update("DELETE FROM characters WHERE id = ?", id));
        goalIds.forEach(id -> jdbcTemplate.update("DELETE FROM goals WHERE id = ?", id));
    }

    @Test
    void 목표_다음_캐릭터를_선택하면_기본_집을_한_번만_생성한다() {
        Long userId = saveUser();
        Long goalId = saveGoal("exercise", 1);
        Long characterId = saveCharacter("cat", 1);

        onboardingCommandService.replaceGoals(userId, new OnboardingGoalsRequest(List.of(goalId), goalId));
        assertThat(activeHouses(userId)).isEmpty();

        onboardingCommandService.selectCharacter(userId, characterId);
        onboardingCommandService.selectCharacter(userId, characterId);

        List<HouseMember> memberships = activeHouses(userId);
        assertThat(memberships).hasSize(1);
        HouseMember membership = memberships.getFirst();
        House house = membership.getHouse();
        assertThat(membership.getRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(house.getOwner().getId()).isEqualTo(userId);
        assertThat(house.getName()).isEqualTo("나의 집");
        assertThat(house.getCoverImageKey()).isEqualTo(DEFAULT_COVER_KEY);
        assertThat(house.getMaxMembers()).isEqualTo(4);
        assertThat(house.getCurrentMemberCount()).isEqualTo(1);
        assertThat(houseGoalRepository.findByHouseIdWithGoal(house.getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactly(goalId);
    }

    @Test
    void 캐릭터_다음_목표를_선택해도_기본_집을_만들고_재저장에는_중복하지_않는다() {
        Long userId = saveUser();
        Long goalId = saveGoal("study", 1);
        Long characterId = saveCharacter("dog", 1);

        onboardingCommandService.selectCharacter(userId, characterId);
        assertThat(activeHouses(userId)).isEmpty();

        OnboardingGoalsRequest request = new OnboardingGoalsRequest(List.of(goalId), goalId);
        onboardingCommandService.replaceGoals(userId, request);
        onboardingCommandService.replaceGoals(userId, request);

        assertThat(activeHouses(userId)).hasSize(1);
    }

    @Test
    void 선택_목표가_넷_이상이면_대표_목표와_정렬상_앞선_목표_두_개를_집에_연결한다() {
        Long userId = saveUser();
        Long g1 = saveGoal("g1", 1);
        Long g2 = saveGoal("g2", 2);
        Long g3 = saveGoal("g3", 3);
        Long primary = saveGoal("g4", 4);
        Long characterId = saveCharacter("rabbit", 1);

        onboardingCommandService.selectCharacter(userId, characterId);
        onboardingCommandService.replaceGoals(userId,
                new OnboardingGoalsRequest(List.of(g1, g2, g3, primary), primary));

        House house = activeHouses(userId).getFirst().getHouse();
        assertThat(houseGoalRepository.findByHouseIdWithGoal(house.getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactlyInAnyOrder(primary, g1, g2);
    }

    @Test
    void 목표와_캐릭터를_동시에_저장해도_기본_집은_하나만_생성된다() throws Exception {
        Long userId = saveUser();
        Long goalId = saveGoal("concurrent", 1);
        Long characterId = saveCharacter("fox", 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> runAfter(start, done, succeeded, () -> onboardingCommandService.replaceGoals(
                userId, new OnboardingGoalsRequest(List.of(goalId), goalId))));
        pool.submit(() -> runAfter(start, done, succeeded,
                () -> onboardingCommandService.selectCharacter(userId, characterId)));

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(succeeded.get()).isEqualTo(2);
        assertThat(activeHouses(userId)).hasSize(1);
    }

    private void runAfter(CountDownLatch start, CountDownLatch done, AtomicInteger succeeded, Runnable action) {
        try {
            start.await();
            action.run();
            succeeded.incrementAndGet();
        } catch (Exception ignored) {
            // 성공 수와 최종 DB 상태로 검증함.
        } finally {
            done.countDown();
        }
    }

    private List<HouseMember> activeHouses(Long userId) {
        return houseMemberRepository.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE);
    }

    private Long saveUser() {
        Long id = userRepository.save(User.signUp()).getId();
        userIds.add(id);
        return id;
    }

    private Long saveGoal(String code, int sortOrder) {
        Goal goal = BeanUtils.instantiateClass(Goal.class);
        ReflectionTestUtils.setField(goal, "code", code);
        ReflectionTestUtils.setField(goal, "name", code + "-name");
        ReflectionTestUtils.setField(goal, "sortOrder", sortOrder);
        ReflectionTestUtils.setField(goal, "active", true);
        Long id = goalRepository.save(goal).getId();
        goalIds.add(id);
        return id;
    }

    private Long saveCharacter(String code, int sortOrder) {
        Character character = characterRepository.save(
                new Character(code, code + "-name", "characters/" + code + ".png", sortOrder, true));
        characterIds.add(character.getId());
        return character.getId();
    }
}
