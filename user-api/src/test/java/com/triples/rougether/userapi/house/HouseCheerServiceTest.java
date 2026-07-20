package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.house.dto.HouseCheerResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCheerService;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class HouseCheerServiceTest {

    private static final Long HOUSE_ID = 1L;
    private static final Long SENDER_ID = 7L;
    private static final Long TARGET_USER_ID = 8L;
    private static final Long TARGET_MEMBERSHIP_ID = 12L;

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseMemberCheerRepository houseMemberCheerRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private HouseCheerService houseCheerService;

    private House aliveHouse() {
        House house = mock(House.class);
        lenient().when(house.isDeleted()).thenReturn(false);
        return house;
    }

    private HouseMember requester(String nickname) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(SENDER_ID);
        lenient().when(user.getNickname()).thenReturn(nickname);
        HouseMember member = mock(HouseMember.class);
        lenient().when(member.isActive()).thenReturn(true);
        lenient().when(member.getUser()).thenReturn(user);
        return member;
    }

    private HouseMember target(Long targetUserId) {
        House house = mock(House.class);
        lenient().when(house.getId()).thenReturn(HOUSE_ID);
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(targetUserId);
        HouseMember member = mock(HouseMember.class);
        lenient().when(member.isActive()).thenReturn(true);
        lenient().when(member.getHouse()).thenReturn(house);
        lenient().when(member.getUser()).thenReturn(user);
        return member;
    }

    private HouseMemberCheer savedCheer(Long targetUserId) {
        User targetUser = mock(User.class);
        lenient().when(targetUser.getId()).thenReturn(targetUserId);
        HouseMemberCheer cheer = mock(HouseMemberCheer.class);
        lenient().when(cheer.getId()).thenReturn(31L);
        lenient().when(cheer.getTarget()).thenReturn(targetUser);
        lenient().when(cheer.getCheerType()).thenReturn("support");
        lenient().when(cheer.getCheerDate()).thenReturn(LocalDate.of(2026, 7, 20));
        return cheer;
    }

    @Test
    void 정의되지_않은_응원_타입은_거부한다() {
        assertThatThrownBy(() -> houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "fighting"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_CHEER_TYPE_INVALID));
        verify(houseMemberCheerRepository, never()).saveAndFlush(any());
    }

    @Test
    void 자기_자신에게는_응원을_보낼_수_없다() {
        House house = aliveHouse();
        when(houseRepository.findById(HOUSE_ID)).thenReturn(Optional.of(house));
        HouseMember me = requester("진형");
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, SENDER_ID))
                .thenReturn(Optional.of(me));
        HouseMember self = target(SENDER_ID); // 대상 = 본인
        when(houseMemberRepository.findById(TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(self));

        assertThatThrownBy(() -> houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_CHEER_SELF));
        verify(houseMemberCheerRepository, never()).saveAndFlush(any());
    }

    @Test
    void 같은_날_같은_타입_중복이면_409로_거부한다() {
        House house = aliveHouse();
        when(houseRepository.findById(HOUSE_ID)).thenReturn(Optional.of(house));
        HouseMember me = requester("진형");
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, SENDER_ID))
                .thenReturn(Optional.of(me));
        HouseMember other = target(TARGET_USER_ID);
        when(houseMemberRepository.findById(TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(other));
        when(houseMemberCheerRepository.existsBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
                any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_CHEER_DUPLICATED));
        verify(houseMemberCheerRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).send(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void 동시_요청의_unique_충돌도_409로_변환한다() {
        House house = aliveHouse();
        when(houseRepository.findById(HOUSE_ID)).thenReturn(Optional.of(house));
        HouseMember me = requester("진형");
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, SENDER_ID))
                .thenReturn(Optional.of(me));
        HouseMember other = target(TARGET_USER_ID);
        when(houseMemberRepository.findById(TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(other));
        when(houseMemberCheerRepository.existsBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
                any(), any(), any(), any())).thenReturn(false);
        when(houseMemberCheerRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_house_member_cheer"));

        assertThatThrownBy(() -> houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_CHEER_DUPLICATED));
        verify(notificationService, never()).send(anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void 성공하면_저장하고_알림_이벤트를_발행한다() {
        House house = aliveHouse();
        when(houseRepository.findById(HOUSE_ID)).thenReturn(Optional.of(house));
        HouseMember me = requester("진형");
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, SENDER_ID))
                .thenReturn(Optional.of(me));
        HouseMember other = target(TARGET_USER_ID);
        when(houseMemberRepository.findById(TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(other));
        when(houseMemberCheerRepository.existsBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
                any(), any(), any(), any())).thenReturn(false);
        HouseMemberCheer cheer = savedCheer(TARGET_USER_ID);
        when(houseMemberCheerRepository.saveAndFlush(any())).thenReturn(cheer);

        HouseCheerResponse response = houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "support");

        assertThat(response.cheerId()).isEqualTo(31L);
        assertThat(response.houseId()).isEqualTo(HOUSE_ID);
        assertThat(response.targetMembershipId()).isEqualTo(TARGET_MEMBERSHIP_ID);
        assertThat(response.targetUserId()).isEqualTo(TARGET_USER_ID);
        assertThat(response.type()).isEqualTo("support");

        // 알림 진입점을 같은 트랜잭션에서 직접 호출한다 - 내역 저장은 응원과 원자적(spec 계약)
        verify(notificationService).send(
                TARGET_USER_ID, NotificationType.FRIEND_CHEER, "응원이 도착했어요", "진형님: 응원해요!", 31L);
    }

    @Test
    void 온보딩_전_보낸이는_알림_표시명이_집친구다() {
        House house = aliveHouse();
        when(houseRepository.findById(HOUSE_ID)).thenReturn(Optional.of(house));
        HouseMember me = requester(null);
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, SENDER_ID))
                .thenReturn(Optional.of(me));
        HouseMember other = target(TARGET_USER_ID);
        when(houseMemberRepository.findById(TARGET_MEMBERSHIP_ID))
                .thenReturn(Optional.of(other));
        when(houseMemberCheerRepository.existsBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
                any(), any(), any(), any())).thenReturn(false);
        HouseMemberCheer cheer = savedCheer(TARGET_USER_ID);
        when(houseMemberCheerRepository.saveAndFlush(any())).thenReturn(cheer);

        houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "support");

        verify(notificationService).send(
                TARGET_USER_ID, NotificationType.FRIEND_CHEER, "응원이 도착했어요", "집 친구님: 응원해요!", 31L);
    }

    @Test
    void 비구성원은_응원을_보낼_수_없다() {
        House house = aliveHouse();
        when(houseRepository.findById(HOUSE_ID)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, SENDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCheerService.cheer(SENDER_ID, HOUSE_ID, TARGET_MEMBERSHIP_ID, "support"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }
}
