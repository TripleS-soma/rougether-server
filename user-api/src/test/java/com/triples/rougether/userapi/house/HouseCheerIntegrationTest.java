package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCheerService;
import com.triples.rougether.userapi.house.service.HouseCheerService.HouseCheerSentEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

// 응원 저장 규칙(#173)을 실제 DB 로 검증. 알림 발송(AFTER_COMMIT)은 커밋이 없는 이 테스트에선
// 이벤트 발행 여부까지만 확인하고, 실제 발송은 HouseCheerNotificationFlowTest 가 커밋 경계로 검증한다.
@SpringBootTest
@Transactional
@RecordApplicationEvents
class HouseCheerIntegrationTest {

    @Autowired private HouseCheerService houseCheerService;
    @Autowired private HouseMemberCheerRepository houseMemberCheerRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ApplicationEvents applicationEvents;

    private User sender;
    private User targetUser;
    private House house;
    private HouseMember targetMember;

    @BeforeEach
    void setUp() {
        sender = userRepository.save(User.signUp("cheer-sender@rougether.dev"));
        targetUser = userRepository.save(User.signUp("cheer-target@rougether.dev"));
        house = houseRepository.save(House.create(
                sender, "응원 하우스", null, null, 4, "CHEER234", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, sender, HouseMemberRole.OWNER));
        targetMember = houseMemberRepository.save(HouseMember.create(house, targetUser, HouseMemberRole.MEMBER));
    }

    @Test
    void 응원을_저장하고_알림_이벤트를_발행한다() {
        HouseCheerResponse response = houseCheerService.cheer(
                sender.getId(), house.getId(), targetMember.getId(), "support");

        assertThat(response.cheerId()).isNotNull();
        assertThat(response.targetUserId()).isEqualTo(targetUser.getId());
        assertThat(response.type()).isEqualTo("support");
        assertThat(houseMemberCheerRepository.findById(response.cheerId())).isPresent();
        assertThat(applicationEvents.stream(HouseCheerSentEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.cheerId()).isEqualTo(response.cheerId());
                    assertThat(event.targetUserId()).isEqualTo(targetUser.getId());
                });
    }

    @Test
    void 같은_날_같은_타입은_한_번만_보낼_수_있고_다른_타입은_허용된다() {
        houseCheerService.cheer(sender.getId(), house.getId(), targetMember.getId(), "support");

        assertThatThrownBy(() -> houseCheerService.cheer(
                sender.getId(), house.getId(), targetMember.getId(), "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_CHEER_DUPLICATED));

        // 타입이 다르면 같은 날에도 허용 (대상당 하루 최대 타입 수만큼)
        HouseCheerResponse best = houseCheerService.cheer(
                sender.getId(), house.getId(), targetMember.getId(), "best");
        assertThat(best.type()).isEqualTo("best");
    }

    @Test
    void 강퇴된_대상에게는_응원을_보낼_수_없다() {
        targetMember.kick();

        assertThatThrownBy(() -> houseCheerService.cheer(
                sender.getId(), house.getId(), targetMember.getId(), "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
    }

    @Test
    void 다른_집의_membership_으로는_보낼_수_없다() {
        House otherHouse = houseRepository.save(House.create(
                targetUser, "다른 하우스", null, null, 4, "CHEER345", Instant.now().plus(Duration.ofDays(7))));
        HouseMember otherMember = houseMemberRepository.save(
                HouseMember.create(otherHouse, targetUser, HouseMemberRole.OWNER));

        assertThatThrownBy(() -> houseCheerService.cheer(
                sender.getId(), house.getId(), otherMember.getId(), "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
    }
}
