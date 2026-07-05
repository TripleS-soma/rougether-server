package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.InviteCodeResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 재발급이 기존 코드를 실제로 무효화하는지 - 컬럼 교체 후 구 코드 조회가 실패하는 것까지 실제 DB(H2)로 증명.
@SpringBootTest
@Transactional
class InviteCodeReissueIntegrationTest {

    private static final String OLD_CODE = "REISSUE2";

    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 재발급하면_기존_코드는_즉시_무효가_되고_새_코드로만_조회된다() {
        User owner = userRepository.save(User.signUp("invite-reissue-test@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "재발급 테스트 하우스", null, null, 4, OLD_CODE, Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));

        // 재발급 전엔 기존 코드로 미리보기 가능
        assertThat(houseJoinService.preview(OLD_CODE).houseId()).isEqualTo(house.getId());

        InviteCodeResponse reissued = houseCommandService.reissueInviteCode(owner.getId(), house.getId());

        assertThat(reissued.inviteCode()).isNotEqualTo(OLD_CODE).hasSize(8);
        // 기존 코드는 즉시 무효
        assertThatThrownBy(() -> houseJoinService.preview(OLD_CODE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
        // 새 코드로는 조회된다
        assertThat(houseJoinService.preview(reissued.inviteCode()).houseId()).isEqualTo(house.getId());
        assertThat(houseJoinService.preview(reissued.inviteCode()).inviteExpired()).isFalse();
    }
}
