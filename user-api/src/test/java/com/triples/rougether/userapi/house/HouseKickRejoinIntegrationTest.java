package com.triples.rougether.userapi.house;

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
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 강퇴 정책 회귀 - 강퇴자는 초대코드/탐색 어느 경로로도 재가입할 수 없음을 실제 DB(H2)로 검증.
@SpringBootTest
@Transactional
class HouseKickRejoinIntegrationTest {

    private static final String CODE = "KICK2345";

    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseMemberCommandService houseMemberCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 강퇴된_구성원은_어느_경로로도_재가입할_수_없다() {
        User owner = userRepository.save(User.signUp("kick-owner@rougether.dev"));
        User member = userRepository.save(User.signUp("kick-member@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "강퇴 테스트 하우스", null, null, 4, CODE, Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        HouseJoinDetailResponse joined = houseJoinService.join(member.getId(), house.getId());
        assertThat(house.getCurrentMemberCount()).isEqualTo(2);

        houseMemberCommandService.kick(owner.getId(), house.getId(), joined.membershipId());

        HouseMember kickedRow = houseMemberRepository.findById(joined.membershipId()).orElseThrow();
        assertThat(kickedRow.getStatus()).isEqualTo(HouseMemberStatus.KICKED);
        assertThat(kickedRow.getLeftAt()).isNotNull();
        assertThat(house.getCurrentMemberCount()).isEqualTo(1);

        // 탐색 참여로도, 초대코드로도 재가입 불가
        assertThatThrownBy(() -> houseJoinService.join(member.getId(), house.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_KICKED_MEMBER));
        assertThatThrownBy(() -> houseJoinService.joinByCode(member.getId(), CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_KICKED_MEMBER));
        assertThat(house.getCurrentMemberCount()).isEqualTo(1); // count 변화 없음
    }
}
