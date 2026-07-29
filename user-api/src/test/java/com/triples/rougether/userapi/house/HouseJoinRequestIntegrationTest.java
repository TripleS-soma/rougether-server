package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class HouseJoinRequestIntegrationTest {

    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseJoinRequestRepository houseJoinRequestRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User applicant;
    private House house;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.signUp("join-request-owner@rougether.dev"));
        applicant = userRepository.save(User.signUp("join-request-applicant@rougether.dev"));
        applicant.changeNickname("신청자");
        house = houseRepository.save(House.create(
                owner, "입주 신청 집", null, null, 4, "JOINREQ2",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
    }

    @Test
    void 탐색_신청은_구성원과_인원수를_바꾸지_않고_수락할_때만_가입시킨다() {
        var request = houseJoinService.requestJoin(applicant.getId(), house.getId());

        assertThat(request.status()).isEqualTo(HouseJoinRequestStatus.PENDING);
        assertThat(house.getCurrentMemberCount()).isEqualTo(1);
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), applicant.getId()))
                .isEmpty();
        assertThat(houseQueryService.getPreview(applicant.getId(), house.getId()).myJoinRequestStatus())
                .isEqualTo(HouseJoinRequestStatus.PENDING);
        assertThat(houseQueryService.explore(applicant.getId(), 0, 100, null, false).items().stream()
                .filter(item -> item.houseId().equals(house.getId()))
                .findFirst().orElseThrow().myJoinRequestStatus())
                .isEqualTo(HouseJoinRequestStatus.PENDING);

        var joined = houseJoinService.acceptRequest(owner.getId(), house.getId(), request.requestId());

        assertThat(joined.status().name()).isEqualTo("ACTIVE");
        assertThat(house.getCurrentMemberCount()).isEqualTo(2);
        assertThat(houseJoinRequestRepository.findById(request.requestId()).orElseThrow().getStatus())
                .isEqualTo(HouseJoinRequestStatus.ACCEPTED);
        assertThat(houseQueryService.getPreview(applicant.getId(), house.getId()).myJoinRequestStatus())
                .isNull();
        assertThat(houseQueryService.explore(applicant.getId(), 0, 100, null, false).items().stream()
                .filter(item -> item.houseId().equals(house.getId()))
                .findFirst().orElseThrow().myJoinRequestStatus())
                .isNull();

        houseMemberRepository.findByHouseIdAndUserId(house.getId(), applicant.getId())
                .orElseThrow()
                .leave();

        assertThat(houseQueryService.getPreview(applicant.getId(), house.getId()).myJoinRequestStatus())
                .isNull();
    }

    @Test
    void 초대코드는_대기_신청이_있어도_즉시가입하고_신청을_종결한다() {
        var request = houseJoinService.requestJoin(applicant.getId(), house.getId());

        houseJoinService.joinByCode(applicant.getId(), "JOINREQ2");

        assertThat(house.getCurrentMemberCount()).isEqualTo(2);
        assertThat(houseJoinRequestRepository.findById(request.requestId()).orElseThrow().getStatus())
                .isEqualTo(HouseJoinRequestStatus.ACCEPTED);
    }

    @Test
    void 방장이_아니면_입주_신청을_처리할_수_없다() {
        User outsider = userRepository.save(User.signUp("join-request-outsider@rougether.dev"));
        var request = houseJoinService.requestJoin(applicant.getId(), house.getId());

        assertThatThrownBy(() -> houseJoinService.rejectRequest(
                outsider.getId(), house.getId(), request.requestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }
}
