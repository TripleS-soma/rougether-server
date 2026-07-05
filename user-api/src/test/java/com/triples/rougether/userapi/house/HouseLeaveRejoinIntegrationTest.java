package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 탈퇴 정책 회귀 - 탈퇴 후 재가입(재활성화)과 마지막 1인 탈퇴 시 집 정리를 실제 DB(H2)로 검증.
@SpringBootTest
@Transactional
class HouseLeaveRejoinIntegrationTest {

    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseMemberCommandService houseMemberCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 탈퇴한_구성원은_같은_membership으로_재가입된다() {
        User owner = userRepository.save(User.signUp("leave-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("leave-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "탈퇴 테스트 하우스", null, null, 4, "LEAVE234", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        HouseJoinDetailResponse joined = houseJoinService.join(member.getId(), house.getId());
        assertThat(house.getCurrentMemberCount()).isEqualTo(2);

        houseMemberCommandService.leave(member.getId(), house.getId());

        HouseMember leftRow = houseMemberRepository.findById(joined.membershipId()).orElseThrow();
        assertThat(leftRow.getStatus()).isEqualTo(HouseMemberStatus.LEFT);
        assertThat(leftRow.getLeftAt()).isNotNull();
        assertThat(house.getCurrentMemberCount()).isEqualTo(1);
        assertThat(house.isDeleted()).isFalse(); // 소유자가 남아 있으니 집 유지

        // 재가입 - 새 row 가 아니라 같은 membership 재활성화
        HouseJoinDetailResponse rejoined = houseJoinService.join(member.getId(), house.getId());
        assertThat(rejoined.membershipId()).isEqualTo(joined.membershipId());
        assertThat(rejoined.status()).isEqualTo(HouseMemberStatus.ACTIVE);
        assertThat(house.getCurrentMemberCount()).isEqualTo(2);
        assertThat(houseMemberRepository.findById(joined.membershipId()).orElseThrow().getLeftAt()).isNull();
    }

    @Test
    void 마지막_1인이_탈퇴하면_집이_정리되고_초대코드도_죽는다() {
        User owner = userRepository.save(User.signUp("leave-last@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "마지막 탈퇴 하우스", null, null, 4, "LEAVE567", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));

        houseMemberCommandService.leave(owner.getId(), house.getId());

        assertThat(house.isDeleted()).isTrue();
        assertThat(house.getCurrentMemberCount()).isZero();
        // 삭제된 집의 초대코드로는 미리보기 불가 (INVITE_CODE_INVALID)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> houseJoinService.preview("LEAVE567"))
                .isInstanceOf(com.triples.rougether.common.error.BusinessException.class);
    }
}
