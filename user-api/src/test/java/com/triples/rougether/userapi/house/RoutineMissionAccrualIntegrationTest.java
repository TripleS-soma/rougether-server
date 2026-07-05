package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.service.RoutineLogService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// 루틴 완료 → 미션 기여 자동 적립 회귀. 이벤트 발행-구독이 실제 컨텍스트에서 배선되는지까지 검증.
@SpringBootTest
@Transactional
class RoutineMissionAccrualIntegrationTest {

    @Autowired private RoutineLogService routineLogService;
    @Autowired private HouseMissionService houseMissionService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository userWalletRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseMissionParticipantRepository participantRepository;

    private record Fixture(User user, Long routineId, Long secondRoutineId, House house, Long missionId) {
    }

    private Fixture fixture() {
        User user = userRepository.save(User.signUp("accrual@rougether.dev"));
        persistCoinWallet(user);
        Long routineId = persistRoutine(user, "아침 운동");
        Long secondRoutineId = persistRoutine(user, "저녁 독서");
        House house = houseRepository.save(House.create(
                user, "적립 하우스", null, null, 4, "ACRL2345", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, user, HouseMemberRole.OWNER));
        HouseMissionResponse mission = houseMissionService.create(user.getId(), house.getId(),
                new HouseMissionCreateRequest("주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 10, null, null));
        return new Fixture(user, routineId, secondRoutineId, house, mission.missionId());
    }

    @Test
    void 루틴을_완료하면_진행중_미션에_기여가_1_쌓인다() {
        Fixture f = fixture();

        routineLogService.complete(f.user().getId(), f.routineId(), new RoutineLogCreateRequest(null));

        assertThat(participantRepository.sumContributionByMissionId(f.missionId())).isEqualTo(1);
    }

    @Test
    void 같은_날_두_번째_루틴_완료는_기여를_더_쌓지_않는다() {
        Fixture f = fixture();

        routineLogService.complete(f.user().getId(), f.routineId(), new RoutineLogCreateRequest(null));
        routineLogService.complete(f.user().getId(), f.secondRoutineId(), new RoutineLogCreateRequest(null));

        // daily-once — 하루 1회만 적립, 두 번째 완료는 조용히 skip (루틴 완료 자체는 성공)
        assertThat(participantRepository.sumContributionByMissionId(f.missionId())).isEqualTo(1);
    }

    @Test
    void 루틴_완료로_적립되면_같은_날_수동_기여는_409() {
        Fixture f = fixture();

        routineLogService.complete(f.user().getId(), f.routineId(), new RoutineLogCreateRequest(null));

        // 수동 contribute 와 daily-once 카운터 공유
        assertThatThrownBy(() -> houseMissionService.contribute(f.user().getId(), f.house().getId(), f.missionId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED));
    }

    @Test
    void 속한_집이_없어도_루틴_완료는_정상_동작한다() {
        User loner = userRepository.save(User.signUp("accrual-loner@rougether.dev"));
        persistCoinWallet(loner);
        Long routineId = persistRoutine(loner, "혼자 루틴");

        routineLogService.complete(loner.getId(), routineId, new RoutineLogCreateRequest(null));

        assertThat(userWalletRepository.findByUserIdAndCurrencyType(loner.getId(), CurrencyType.COIN)
                .orElseThrow().getBalance()).isEqualTo(10);
    }

    private Long persistRoutine(User owner, String title) {
        return routineRepository.save(Routine.create(
                owner, null, title, AuthType.CHECK, null, null, null, null, null)).getId();
    }

    private void persistCoinWallet(User owner) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", 0);
        userWalletRepository.save(wallet);
    }
}
