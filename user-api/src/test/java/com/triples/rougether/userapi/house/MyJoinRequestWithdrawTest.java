package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseJoinRequestResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 입주 신청 철회 의미 검증 - PENDING 철회(행 삭제)·재신청 가능·처리된 신청 409·타인/미존재 404.
@SpringBootTest
@Transactional
class MyJoinRequestWithdrawTest {

    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseJoinRequestRepository houseJoinRequestRepository;
    @Autowired private UserRepository userRepository;

    private User me;
    private User owner;
    private House house;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.signUp("withdraw-test-me@rougether.dev"));
        owner = userRepository.save(User.signUp("withdraw-test-owner@rougether.dev"));
        house = houseRepository.save(House.create(
                owner, "철회 테스트 집", null, null, 4, "WDRAW222",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
    }

    @Test
    void PENDING_신청을_철회하면_행이_삭제되고_재신청할_수_있다() {
        HouseJoinRequestResponse request = houseJoinService.requestJoin(me.getId(), house.getId());

        houseJoinService.withdrawRequest(me.getId(), request.requestId());

        assertThat(houseJoinRequestRepository.findById(request.requestId())).isEmpty();

        HouseJoinRequestResponse again = houseJoinService.requestJoin(me.getId(), house.getId());
        assertThat(again.status()).isEqualTo(HouseJoinRequestStatus.PENDING);
        assertThat(again.requestId()).isNotEqualTo(request.requestId());
    }

    @Test
    void 이미_수락된_신청은_철회할_수_없다() {
        HouseJoinRequestResponse request = houseJoinService.requestJoin(me.getId(), house.getId());
        houseJoinService.acceptRequest(owner.getId(), house.getId(), request.requestId());

        assertThatThrownBy(() -> houseJoinService.withdrawRequest(me.getId(), request.requestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_PENDING));
    }

    @Test
    void 이미_거절된_신청은_철회할_수_없다() {
        HouseJoinRequestResponse request = houseJoinService.requestJoin(me.getId(), house.getId());
        houseJoinService.rejectRequest(owner.getId(), house.getId(), request.requestId());

        assertThatThrownBy(() -> houseJoinService.withdrawRequest(me.getId(), request.requestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_PENDING));
        assertThat(houseJoinRequestRepository.findById(request.requestId())).isPresent(); // 거절 이력은 남는다
    }

    @Test
    void 타인의_신청은_404로_숨긴다() {
        HouseJoinRequestResponse request = houseJoinService.requestJoin(me.getId(), house.getId());
        User other = userRepository.save(User.signUp("withdraw-test-other@rougether.dev"));

        assertThatThrownBy(() -> houseJoinService.withdrawRequest(other.getId(), request.requestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_FOUND));
        assertThat(houseJoinRequestRepository.findById(request.requestId())).isPresent(); // 삭제되지 않는다
    }

    @Test
    void 존재하지_않는_신청은_404다() {
        assertThatThrownBy(() -> houseJoinService.withdrawRequest(me.getId(), 999_999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_JOIN_REQUEST_NOT_FOUND));
    }
}
