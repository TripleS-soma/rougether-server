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
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.service.SignupService;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
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
    @Autowired private SignupService signupService;
    @Autowired private HouseMemberCommandService houseMemberCommandService;
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
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id IN "
                    + "(SELECT id FROM house WHERE owner_user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM house_members WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house WHERE owner_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_goals WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_characters WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        characterIds.forEach(id -> jdbcTemplate.update("DELETE FROM characters WHERE id = ?", id));
        goalIds.forEach(id -> jdbcTemplate.update("DELETE FROM goals WHERE id = ?", id));
    }

    // #322: 기본 집은 가입 시점에 이미 있고(목표 없음), 온보딩 목표 저장이 집 목표를 1회 채운다. 캐릭터 선택은 집과 무관.

    @Test
    void 가입_직후_기본_집이_있고_목표는_비어_있다() {
        Long userId = saveUser();

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
        assertThat(houseGoalRepository.findByHouseId(house.getId())).isEmpty();
    }

    @Test
    void 목표를_저장하면_기본_집_목표가_채워지고_재저장해도_바뀌지_않는다() {
        Long userId = saveUser();
        Long goalId = saveGoal("exercise", 1);
        Long other = saveGoal("other", 2);
        House house = activeHouses(userId).getFirst().getHouse();

        onboardingCommandService.replaceGoals(userId, new OnboardingGoalsRequest(List.of(goalId), goalId));
        assertThat(houseGoalRepository.findByHouseIdWithGoal(house.getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactly(goalId);

        // 두 번째 저장(목표 변경)은 집 목표를 건드리지 않음 — 1회성
        onboardingCommandService.replaceGoals(userId, new OnboardingGoalsRequest(List.of(other), other));
        assertThat(houseGoalRepository.findByHouseIdWithGoal(house.getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactly(goalId);
        // 집도 여전히 하나
        assertThat(activeHouses(userId)).hasSize(1);
    }

    @Test
    void 선택_목표가_넷_이상이면_대표_목표와_정렬상_앞선_목표_두_개를_집에_연결한다() {
        Long userId = saveUser();
        Long g1 = saveGoal("g1", 1);
        Long g2 = saveGoal("g2", 2);
        Long g3 = saveGoal("g3", 3);
        Long primary = saveGoal("g4", 4);

        onboardingCommandService.replaceGoals(userId,
                new OnboardingGoalsRequest(List.of(g1, g2, g3, primary), primary));

        House house = activeHouses(userId).getFirst().getHouse();
        assertThat(houseGoalRepository.findByHouseIdWithGoal(house.getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactlyInAnyOrder(primary, g1, g2);
    }

    @Test
    void 캐릭터_선택은_기본_집에_영향을_주지_않는다() {
        Long userId = saveUser();
        Long goalId = saveGoal("study", 1);
        Long characterId = saveCharacter("dog", 1);
        House house = activeHouses(userId).getFirst().getHouse();

        // 캐릭터 먼저 → 집 그대로, 목표 비어 있음
        onboardingCommandService.selectCharacter(userId, characterId);
        onboardingCommandService.selectCharacter(userId, characterId);
        assertThat(activeHouses(userId)).hasSize(1);
        assertThat(houseGoalRepository.findByHouseId(house.getId())).isEmpty();

        // 목표 저장 → 채워짐. 그 뒤 캐릭터 재선택해도 집·목표 불변
        onboardingCommandService.replaceGoals(userId, new OnboardingGoalsRequest(List.of(goalId), goalId));
        onboardingCommandService.selectCharacter(userId, characterId);
        assertThat(activeHouses(userId)).hasSize(1);
        assertThat(houseGoalRepository.findByHouseIdWithGoal(house.getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactly(goalId);
    }

    @Test
    void 기본_집을_나간_뒤_목표를_저장해도_집을_다시_만들거나_채우지_않는다() {
        Long userId = saveUser();
        Long goalId = saveGoal("left", 1);
        House house = activeHouses(userId).getFirst().getHouse();

        // 혼자인 집을 나가면 해체(soft delete) — 재생성 없음
        houseMemberCommandService.leave(userId, house.getId());
        assertThat(activeHouses(userId)).isEmpty();

        onboardingCommandService.replaceGoals(userId, new OnboardingGoalsRequest(List.of(goalId), goalId));

        assertThat(activeHouses(userId)).isEmpty();
        assertThat(houseGoalRepository.findByHouseId(house.getId())).isEmpty();
    }

    @Test
    void 목표와_캐릭터를_동시에_저장해도_집은_하나이고_목표는_한_번만_채워진다() throws Exception {
        Long userId = saveUser();
        Long goalId = saveGoal("concurrent", 1);
        Long characterId = saveCharacter("fox", 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);

        OnboardingGoalsRequest request = new OnboardingGoalsRequest(List.of(goalId), goalId);
        pool.submit(() -> runAfter(start, done, succeeded, () -> onboardingCommandService.replaceGoals(userId, request)));
        pool.submit(() -> runAfter(start, done, succeeded, () -> onboardingCommandService.replaceGoals(userId, request)));
        pool.submit(() -> runAfter(start, done, succeeded,
                () -> onboardingCommandService.selectCharacter(userId, characterId)));

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(succeeded.get()).isEqualTo(3);
        List<HouseMember> memberships = activeHouses(userId);
        assertThat(memberships).hasSize(1);
        assertThat(houseGoalRepository.findByHouseIdWithGoal(memberships.getFirst().getHouse().getId()))
                .extracting(houseGoal -> houseGoal.getGoal().getId())
                .containsExactly(goalId);
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

    // 가입 경로로 만들어야 기본 집이 함께 생긴다(#322)
    private Long saveUser() {
        Long id = signupService.register(null).getId();
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
