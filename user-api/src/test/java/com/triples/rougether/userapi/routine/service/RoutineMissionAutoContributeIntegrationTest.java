package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyContributionRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyRewardRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 연동 루틴(routines.house_mission_id) 완료 시 단체미션 자동 기여 검증.
// 기여 불가 사유는 완료를 실패시키지 않고 조용히 건너뛰어야 한다(houseMissionContribution=null).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineMissionAutoContributeIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private RoutineRepository routineRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private RoutineLogRepository routineLogRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private StreakRepository streakRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseMissionRepository houseMissionRepository;
    @Autowired private HouseMissionParticipantRepository participantRepository;
    @Autowired private HouseMissionDailyContributionRepository dailyContributionRepository;
    @Autowired private HouseMissionDailyRewardRepository dailyRewardRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private RoutineLogService service;
    private HouseMissionService houseMissionService;
    private User user;
    private Long userId;
    private House house;
    private HouseMember membership;
    private Long missionId;

    @BeforeEach
    void setUp() {
        houseMissionService = new HouseMissionService(
                houseRepository, houseMemberRepository, houseMissionRepository,
                participantRepository, dailyContributionRepository, dailyRewardRepository,
                mock(BannedWordChecker.class), mock(NotificationService.class), routineRepository);
        service = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository,
                new DailyRewardService(routineLogRepository, todoRepository),
                new TransactionTemplate(transactionManager), houseMissionService);

        user = userRepository.save(User.signUp());
        userId = user.getId();
        persistWallet(user);
        house = houseRepository.save(House.create(user, "자동기여 집", null, null, 4,
                "AUTOCON1", Instant.now().plus(Duration.ofDays(7))));
        membership = houseMemberRepository.save(HouseMember.create(house, user, HouseMemberRole.OWNER));
        missionId = houseMissionRepository.save(HouseMission.create(
                house, "다같이 운동", HouseMissionType.WEEKLY_MEMBER_COUNT, 3, null, null)).getId();
    }

    @Test
    void 연동_루틴_오늘_완료는_미션에_자동_기여된다() {
        Long routineId = persistLinkedRoutine(missionId);

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.houseMissionContribution()).isNotNull();
        assertThat(response.houseMissionContribution().missionId()).isEqualTo(missionId);
        assertThat(response.houseMissionContribution().myContribution()).isEqualTo(1);
        assertThat(response.houseMissionContribution().currentValue()).isEqualTo(1);
        assertThat(response.houseMissionContribution().achieved()).isFalse();
        assertThat(dailyContributionRepository.existsByMissionIdAndMemberIdAndContributionDate(
                missionId, membership.getId(), TODAY)).isTrue();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isEqualTo(1);
    }

    @Test
    void 같은_미션에_연동된_두번째_루틴_완료는_하루_1회_규칙으로_건너뛴다() {
        Long first = persistLinkedRoutine(missionId);
        Long second = persistLinkedRoutine(missionId);
        service.complete(userId, first, new RoutineLogCreateRequest(null));

        RoutineLogResponse response = service.complete(userId, second, new RoutineLogCreateRequest(null));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.houseMissionContribution()).isNull();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isEqualTo(1);
    }

    @Test
    void 미연동_루틴_완료는_기여하지_않는다() {
        Long routineId = persistLinkedRoutine(null);

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.houseMissionContribution()).isNull();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isZero();
    }

    @Test
    void 삭제된_미션_연동_루틴도_완료는_성공하고_기여만_건너뛴다() {
        Long routineId = persistLinkedRoutine(missionId);
        houseMissionRepository.findById(missionId).orElseThrow().softDelete(Instant.now());

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.houseMissionContribution()).isNull();
    }

    @Test
    void 집을_떠난_뒤_완료는_성공하고_기여만_건너뛴다() {
        Long routineId = persistLinkedRoutine(missionId);
        membership.leave();

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.houseMissionContribution()).isNull();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isZero();
    }

    @Test
    void 과거_날짜_완료는_기여하지_않는다() {
        Long routineId = persistLinkedRoutine(missionId);

        RoutineLogResponse response = service.complete(userId, routineId,
                new RoutineLogCreateRequest(TODAY.minusDays(1)));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.houseMissionContribution()).isNull();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isZero();
    }

    @Test
    void 직접_기여한_날의_연동_루틴_완료는_건너뛴다() {
        Long routineId = persistLinkedRoutine(missionId);
        // 미션 화면에서 직접 수행 체크한 상황 재현 — 같은 (mission, member, today) 기여가 이미 있음
        HouseMissionService direct = new HouseMissionService(
                houseRepository, houseMemberRepository, houseMissionRepository,
                participantRepository, dailyContributionRepository, dailyRewardRepository,
                mock(BannedWordChecker.class), mock(NotificationService.class), routineRepository);
        direct.contribute(userId, house.getId(), missionId);

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.houseMissionContribution()).isNull();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isEqualTo(1);
    }

    @Test
    void 완료_취소_후_재완료해도_기여는_한_번만_남는다() {
        Long routineId = persistLinkedRoutine(missionId);
        service.complete(userId, routineId, new RoutineLogCreateRequest(null));
        // 기여 미회수 정책 - 취소해도 오늘 기여는 남고, 재완료는 하루 1회 규칙으로 건너뛴다.
        service.cancel(userId, routineId, TODAY);

        RoutineLogResponse recompleted = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(recompleted.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(recompleted.houseMissionContribution()).isNull();
        assertThat(participantRepository.sumContributionByMissionId(missionId)).isEqualTo(1);
        assertThat(dailyContributionRepository.existsByMissionIdAndMemberIdAndContributionDate(
                missionId, membership.getId(), TODAY)).isTrue();
    }

    @Test
    void 자동_기여로_목표에_도달하면_achieved가_true다() {
        Long routineId = persistLinkedRoutine(targetOneMissionId());

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.houseMissionContribution()).isNotNull();
        assertThat(response.houseMissionContribution().achieved()).isTrue();
    }

    @Test
    void 미션을_삭제하면_전_구성원의_연동_루틴이_해제된다() {
        Long routineId = persistLinkedRoutine(missionId);

        houseMissionService.delete(userId, house.getId(), missionId);

        Routine routine = routineRepository.findById(routineId).orElseThrow();
        assertThat(routine.getHouseMissionId()).isNull();
        assertThat(routine.getDeletedAt()).isNull();
    }

    @Test
    void 집을_떠나면_그_집과의_루틴_카테고리_연동이_해제된다() {
        Long routineId = persistLinkedRoutine(missionId);
        Category category = categoryRepository.save(
                Category.create(user, "우리집 미션", null, null, 0, PrivacyScope.HOUSE));
        category.linkHouse(house.getId());
        HouseMemberCommandService memberCommand = new HouseMemberCommandService(
                houseRepository, houseMemberRepository, mock(NotificationService.class),
                routineRepository, categoryRepository);

        // 소유자 단독 구성원 - 탈퇴와 함께 집이 정리(soft delete)되는 경우까지 커버
        memberCommand.leave(userId, house.getId());

        assertThat(routineRepository.findById(routineId).orElseThrow().getHouseMissionId()).isNull();
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getHouseId()).isNull();
        // 루틴·카테고리 자체는 남는다(연동만 해제)
        assertThat(routineRepository.findById(routineId).orElseThrow().getDeletedAt()).isNull();
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    private Long targetOneMissionId() {
        return houseMissionRepository.save(HouseMission.create(
                house, "한 번이면 달성", HouseMissionType.WEEKLY_MEMBER_COUNT, 1, null, null)).getId();
    }

    private Long persistLinkedRoutine(Long linkedMissionId) {
        Routine routine = Routine.create(user, null, "연동 루틴", AuthType.CHECK,
                null, null, null, null, null);
        routine.linkHouseMission(linkedMissionId);
        return routineRepository.save(routine).getId();
    }

    private void persistWallet(User owner) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", 0);
        userWalletRepository.save(wallet);
    }
}
