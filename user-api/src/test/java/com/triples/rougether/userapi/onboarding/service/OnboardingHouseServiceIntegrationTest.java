package com.triples.rougether.userapi.onboarding.service;

import static com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice.AUTO_JOIN;
import static com.triples.rougether.domain.onboarding.entity.OnboardingHouseResult.JOINED;
import static com.triples.rougether.domain.onboarding.entity.OnboardingHouseResult.NO_MATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseResult;
import com.triples.rougether.domain.onboarding.repository.OnboardingHouseSelectionRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.house.service.HouseAutoJoinService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.onboarding.dto.OnboardingHouseResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class OnboardingHouseServiceIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokens;
    @MockitoSpyBean
    private NotificationService notifications;
    @Autowired private OnboardingHouseService service;
    @Autowired private OnboardingHouseTransactionService transactions;
    @Autowired private HouseAutoJoinService autoJoinService;
    @Autowired private HouseJoinService joinService;
    @Autowired private HouseRepository houses;
    @Autowired private HouseMemberRepository members;
    @Autowired private UserRepository users;
    @Autowired private OnboardingHouseSelectionRepository selections;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> createdHouses = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private Long user() {
        Long id = users.save(User.signUp(UUID.randomUUID() + "@test.dev")).getId();
        createdUsers.add(id);
        return id;
    }

    private Long house(Long owner, boolean isPublic, boolean enabled, int capacity) {
        Long id = tx().execute(status -> {
            User user = users.findById(owner).orElseThrow();
            user.changeNickname("집주인");
            House house = House.create(user, "테스트 집", null, null, capacity,
                    UUID.randomUUID().toString(), Instant.now().plusSeconds(3600));
            house.updateSettings(null, null, null, null, isPublic);
            house.changeOnboardingAutoJoinEnabled(enabled);
            houses.save(house);
            members.save(HouseMember.create(house, user, HouseMemberRole.OWNER));
            return house.getId();
        });
        createdHouses.add(id);
        return id;
    }

    @AfterEach
    void disableFixtures() {
        tx().executeWithoutResult(status -> {
            // 테스트가 새로 생성한 fallback 집도 다음 테스트의 후보에서 제외함.
            for (Long userId : createdUsers) {
                members.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE)
                        .forEach(member -> member.getHouse().changeOnboardingAutoJoinEnabled(false));
            }
            houses.findAllById(createdHouses).forEach(house -> {
                house.changeOnboardingAutoJoinEnabled(false);
                house.softDelete();
            });
            users.findAllById(createdUsers).stream().filter(User::isBot)
                    .forEach(user -> user.softDelete(Instant.now()));
        });
    }

    @Test
    void 좋아요는_허용한_공개집에_합류하고_기존_개인집은_유지한다() {
        Long owner = user();
        Long target = house(owner, true, true, 4);
        Long userId = user();
        Long personal = house(userId, false, false, 4);
        assertThat(service.get(userId).completed()).isFalse();
        var result = service.select(userId, AUTO_JOIN);
        assertThat(result.result()).isEqualTo(JOINED);
        assertThat(result.houseId()).isEqualTo(target);
        assertThat(houses.findById(target).orElseThrow().getCurrentMemberCount()).isEqualTo(2);
        assertThat(members.findByHouseIdAndUserId(personal, userId).orElseThrow().isActive()).isTrue();
        assertThat(houses.findById(personal).orElseThrow().isPublic()).isFalse();
        assertThat(service.select(userId, AUTO_JOIN)).isEqualTo(result);
        assertThat(service.get(userId)).isEqualTo(result);
        assertThat(houses.findById(target).orElseThrow().getCurrentMemberCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.select(userId, OnboardingHouseChoice.PERSONAL))
                .isInstanceOf(BusinessException.class).hasMessageContaining("이미 집 선택");
    }

    @Test
    void 괜찮아요는_개인집을_비공개로_유지한다() {
        Long target = house(user(), true, true, 4);
        Long userId = user();
        Long personal = house(userId, false, false, 4);
        var result = service.select(userId, OnboardingHouseChoice.PERSONAL);
        assertThat(result.result()).isEqualTo(OnboardingHouseResult.PERSONAL);
        assertThat(result.houseId()).isEqualTo(personal);
        assertThat(houses.findById(personal).orElseThrow().isPublic()).isFalse();
        assertThat(houses.findById(personal).orElseThrow().isOnboardingAutoJoinEnabled()).isFalse();
        assertThat(members.findByHouseIdAndUserId(target, userId)).isEmpty();
    }

    @Test
    void 매칭실패면_개인집을_자유가입_공개집으로_만들어_다음_사용자가_합류한다() {
        Long first = user();
        Long personal = house(first, false, false, 4);
        var result = service.select(first, AUTO_JOIN);
        assertThat(result.result()).isEqualTo(NO_MATCH);
        assertThat(result.houseId()).isEqualTo(personal);
        assertThat(houses.findById(personal).orElseThrow().isPublic()).isTrue();
        assertThat(houses.findById(personal).orElseThrow().isOnboardingAutoJoinEnabled()).isTrue();
        Long next = user();
        assertThat(service.select(next, AUTO_JOIN).houseId()).isEqualTo(personal);
        assertThat(service.select(first, AUTO_JOIN)).isEqualTo(result);
    }

    @Test
    void 개인집이_없는_계정도_새_비공개집을_한번만_만든다() {
        Long userId = user();
        var result = service.select(userId, OnboardingHouseChoice.PERSONAL);
        assertThat(result.result()).isEqualTo(OnboardingHouseResult.PERSONAL);
        assertThat(service.select(userId, OnboardingHouseChoice.PERSONAL)).isEqualTo(result);
        assertThat(houses.findById(result.houseId()).orElseThrow().isPublic()).isFalse();
    }

    @Test
    void 비공개_미허용_만석_삭제_강퇴_신청이력_집을_제외한다() {
        Long userId = user();
        house(user(), false, true, 4);
        house(user(), true, false, 4);
        house(user(), true, true, 1);
        Long deleted = house(user(), true, true, 4);
        Long kicked = house(user(), true, true, 4);
        Long pending = house(user(), true, true, 4);
        tx().executeWithoutResult(status -> {
            houses.findById(deleted).orElseThrow().softDelete();
            var member = HouseMember.create(houses.findById(kicked).orElseThrow(), users.findById(userId).orElseThrow(), HouseMemberRole.MEMBER);
            member.kick();
            members.save(member);
        });
        joinService.requestJoin(userId, pending);
        var result = service.select(userId, AUTO_JOIN);
        assertThat(result.result()).isEqualTo(NO_MATCH);
        assertThat(members.findByHouseIdAndUserId(kicked, userId).orElseThrow().isKicked()).isTrue();
        assertThat(houses.findById(pending).orElseThrow().getCurrentMemberCount()).isEqualTo(1);
    }

    @Test
    void 후보_조회후_허용을_취소하거나_비공개가_되면_합류하지_않는다() {
        Long owner = user();
        Long target = house(owner, true, true, 4);
        Long userId = user();
        autoJoinService.update(owner, target, false);
        assertThat(transactions.tryJoin(userId, target)).isNull();
        autoJoinService.update(owner, target, true);
        tx().executeWithoutResult(status -> houses.findById(target).orElseThrow().updateSettings(null, null, null, null, false));
        assertThat(transactions.tryJoin(userId, target)).isNull();
        assertThat(selections.findById(userId)).isEmpty();
    }

    @Test
    void 자동입주_설정은_집주인만_변경한다() {
        Long owner = user();
        Long target = house(owner, true, false, 4);
        Long outsider = user();
        assertThatThrownBy(() -> autoJoinService.update(outsider, target, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> autoJoinService.get(outsider, target)).isInstanceOf(BusinessException.class);
        assertThat(autoJoinService.update(owner, target, true).enabled()).isTrue();
        assertThat(autoJoinService.get(owner, target).enabled()).isTrue();
    }

    @Test
    void 탈퇴한_사용자는_선택할_수_없다() {
        Long userId = user();
        tx().executeWithoutResult(status -> users.findById(userId).orElseThrow().softDelete(Instant.now()));
        assertThatThrownBy(() -> service.select(userId, AUTO_JOIN)).isInstanceOf(BusinessException.class);
        assertThat(selections.findById(userId)).isEmpty();
    }

    @Test
    void 동시_재요청도_가입과_선택을_한번만_저장한다() throws Exception {
        Long target = house(user(), true, true, 10);
        Long userId = user();
        var results = race(List.of(userId, userId, userId, userId));
        assertThat(results).allMatch(result -> result.result() == JOINED && result.houseId().equals(target));
        assertThat(results.stream().map(OnboardingHouseResponse::membershipId).distinct().count()).isEqualTo(1);
        assertThat(houses.findById(target).orElseThrow().getCurrentMemberCount()).isEqualTo(2);
    }

    @Test
    void 마지막_자리에_동시_합류해도_정원을_넘지_않는다() throws Exception {
        Long target = house(user(), true, true, 2);
        var results = race(List.of(user(), user()));
        assertThat(results.stream().filter(result -> result.houseId().equals(target)).count()).isEqualTo(1);
        assertThat(houses.findById(target).orElseThrow().getCurrentMemberCount()).isEqualTo(2);
        assertThat(members.countByHouseIdAndStatus(target, HouseMemberStatus.ACTIVE)).isEqualTo(2);
        assertThat(results).extracting(OnboardingHouseResponse::result).containsExactlyInAnyOrder(JOINED, NO_MATCH);
    }

    @Test
    void 입주알림_저장_실패는_가입_정원_선택을_함께_롤백한다() {
        Long target = house(user(), true, true, 4);
        Long userId = user();
        Mockito.doThrow(new IllegalStateException("notification failed"))
                .when(notifications).send(ArgumentMatchers.anyLong(),
                        ArgumentMatchers.any(), ArgumentMatchers.anyLong());
        try {
            assertThatThrownBy(() -> service.select(userId, AUTO_JOIN)).isInstanceOf(IllegalStateException.class);
            assertThat(selections.findById(userId)).isEmpty();
            assertThat(members.findByHouseIdAndUserId(target, userId)).isEmpty();
            assertThat(houses.findById(target).orElseThrow().getCurrentMemberCount()).isEqualTo(1);
        } finally {
            Mockito.reset(notifications);
        }
        assertThat(service.select(userId, AUTO_JOIN).result()).isEqualTo(JOINED);
    }

    @Test
    void 봇으로_만석인_집은_봇이_자리를_양보한다() {
        Long target = house(user(), true, true, 2);
        Long bot = users.save(User.bot(UUID.randomUUID().toString(), "동거봇", null)).getId();
        createdUsers.add(bot);
        tx().executeWithoutResult(status -> {
            House house = houses.findById(target).orElseThrow();
            members.save(HouseMember.create(house, users.findById(bot).orElseThrow(), HouseMemberRole.MEMBER));
            house.increaseMemberCount();
        });
        assertThat(service.select(user(), AUTO_JOIN).houseId()).isEqualTo(target);
        assertThat(members.findByHouseIdAndUserId(target, bot).orElseThrow().getStatus()).isEqualTo(HouseMemberStatus.LEFT);
        assertThat(houses.findById(target).orElseThrow().getCurrentMemberCount()).isEqualTo(2);
    }

    @Test
    void HTTP_선택과_결과조회는_인증된_본인에게_저장된다() throws Exception {
        Long userId = user();
        Long personal = house(userId, false, false, 4);
        String token = tokens.issueAccessToken(userId, MemberRole.NORMAL);
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/onboarding/house")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"AUTO_JOIN\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.result").value("NO_MATCH"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.houseId").value(personal));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/onboarding/house")
                        .header("Authorization", "Bearer " + token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.completed").value(true));
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/onboarding/house")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"PERSONAL\"}"))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code")
                        .value("ONBOARDING_HOUSE_ALREADY_SELECTED"));
    }

    @Test
    void HTTP_인증누락과_잘못된_선택값은_거부한다() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/onboarding/house")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"AUTO_JOIN\"}"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
        Long userId = user();
        String token = tokens.issueAccessToken(userId, MemberRole.NORMAL);
        for (String body : List.of("{}", "{\"choice\":null}", "{\"choice\":\"UNKNOWN\"}")) {
            mvc.perform(MockMvcRequestBuilders.put("/api/v1/onboarding/house")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(MockMvcResultMatchers.status().isBadRequest());
        }
        assertThat(selections.findById(userId)).isEmpty();
    }

    @Test
    void HTTP_자동입주설정은_필수값과_소유권을_검증한다() throws Exception {
        Long owner = user();
        Long target = house(owner, true, false, 4);
        String token = tokens.issueAccessToken(owner, MemberRole.NORMAL);
        String outsider = tokens.issueAccessToken(user(), MemberRole.NORMAL);
        String path = "/api/v1/houses/" + target + "/auto-join";
        mvc.perform(MockMvcRequestBuilders.put(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.enabled").value(true));
        mvc.perform(MockMvcRequestBuilders.put(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.put(path)
                        .header("Authorization", "Bearer " + outsider)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        assertThat(autoJoinService.get(owner, target).enabled()).isTrue();
    }

    @Test
    void 다른_집필드의_오래된_조회가_자동입주_허용설정을_덮어쓰지_않는다() {
        Long owner = user();
        Long target = house(owner, true, false, 4);
        tx().executeWithoutResult(status -> {
            House stale = houses.findById(target).orElseThrow();
            TransactionTemplate other = tx();
            other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(inner -> autoJoinService.update(owner, target, true));
            stale.updateSettings("새 집 이름", null, null, null, null);
        });
        assertThat(autoJoinService.get(owner, target).enabled()).isTrue();
        assertThat(houses.findById(target).orElseThrow().getName()).isEqualTo("새 집 이름");
    }

    @Test
    void 봇만_남은_집은_자동합류_대상에서_제외한다() {
        Long owner = user();
        Long target = house(owner, true, true, 4);
        tx().executeWithoutResult(status -> members.findByHouseIdAndUserId(target, owner).orElseThrow().leave());
        Long userId = user();
        assertThat(transactions.tryJoin(userId, target)).isNull();
        assertThat(service.select(userId, AUTO_JOIN).result()).isEqualTo(NO_MATCH);
    }

    @Test
    void 개인집_생성_재요청이_경합해도_하나만_생성한다() throws Exception {
        Long userId = user();
        var results = race(List.of(userId, userId, userId));
        assertThat(results).allMatch(result -> result.result() == NO_MATCH);
        assertThat(results.stream().map(OnboardingHouseResponse::houseId).distinct().count()).isEqualTo(1);
        tx().executeWithoutResult(status -> assertThat(members.findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE))
                .hasSize(1));
    }

    private List<OnboardingHouseResponse> race(List<Long> userIds) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(userIds.size())) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = userIds.stream().map(userId -> pool.submit(() -> {
                start.await();
                return service.select(userId, AUTO_JOIN);
            })).toList();
            start.countDown();
            List<OnboardingHouseResponse> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
