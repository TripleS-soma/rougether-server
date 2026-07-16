package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.ErrorCode;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 단체 미션 삭제 회귀 - 소유자 전용·soft delete·COMPLETED 삭제 불가·삭제 후 접근 차단을 실제 DB 로 검증.
@SpringBootTest
@Transactional
class HouseMissionDeleteIntegrationTest {

    @Autowired private HouseMissionService houseMissionService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseMissionParticipantRepository participantRepository;
    @Autowired private UserRepository userRepository;

    private record Fixture(User owner, User member, House house) {
    }

    private Fixture fixture() {
        User owner = userRepository.save(User.signUp("mission-del-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("mission-del-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "삭제 하우스", null, null, 4, "MDEL2345", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        return new Fixture(owner, member, house);
    }

    private HouseMissionResponse mission(Fixture f, int target) {
        return houseMissionService.create(f.owner().getId(), f.house().getId(),
                new HouseMissionCreateRequest("삭제 대상", HouseMissionType.WEEKLY_MEMBER_COUNT, target, null, null));
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    void 소유자는_기여가_있어도_진행_중_미션을_삭제할_수_있다() {
        Fixture f = fixture();
        HouseMissionResponse created = mission(f, 5);
        houseMissionService.contribute(f.member().getId(), f.house().getId(), created.missionId());

        houseMissionService.delete(f.owner().getId(), f.house().getId(), created.missionId());

        // 목록·상세에서 사라진다
        assertThat(houseMissionService.getMissions(f.owner().getId(), f.house().getId()).items()).isEmpty();
        assertThatThrownBy(() -> houseMissionService.getMission(
                f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        // 기여 이력 자체는 보존된다 (soft delete)
        assertThat(participantRepository.sumContributionByMissionId(created.missionId())).isEqualTo(1);
    }

    @Test
    void 삭제된_미션에는_기여도_보상수령도_할_수_없다() {
        Fixture f = fixture();
        HouseMissionResponse created = mission(f, 1);
        houseMissionService.delete(f.owner().getId(), f.house().getId(), created.missionId());

        assertThatThrownBy(() -> houseMissionService.contribute(
                f.member().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        assertThatThrownBy(() -> houseMissionService.claim(
                f.member().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
    }

    @Test
    void 일반_구성원은_삭제할_수_없다() {
        Fixture f = fixture();
        HouseMissionResponse created = mission(f, 5);

        assertThatThrownBy(() -> houseMissionService.delete(
                f.member().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }

    @Test
    void 보상을_수령한_미션은_삭제할_수_없다() {
        Fixture f = fixture();
        HouseMissionResponse created = mission(f, 1);
        houseMissionService.contribute(f.owner().getId(), f.house().getId(), created.missionId());
        houseMissionService.claim(f.owner().getId(), f.house().getId(), created.missionId());

        assertThatThrownBy(() -> houseMissionService.delete(
                f.owner().getId(), f.house().getId(), created.missionId()))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED));
        // 여전히 목록에 남아 있다
        assertThat(houseMissionService.getMissions(f.owner().getId(), f.house().getId()).items()).hasSize(1);
    }

    @Test
    void 없는_미션이면_404() {
        Fixture f = fixture();

        assertThatThrownBy(() -> houseMissionService.delete(
                f.owner().getId(), f.house().getId(), 999999L))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
    }
}
