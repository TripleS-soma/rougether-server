package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.bot.BotResidencyService;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseLeaveServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private RoutineRepository routineRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    private HouseMemberCommandService houseMemberCommandService;

    @BeforeEach
    void setUp() {
        // 봇 거주 규칙(#309)은 실제 서비스를 mock 리포지토리 위에 올려 "사람 0명 → 해체" 판정을 그대로 태운다.
        houseMemberCommandService = new HouseMemberCommandService(
                houseRepository, houseMemberRepository, notificationService, routineRepository, categoryRepository,
                new BotResidencyService(userRepository, houseMemberRepository));
    }

    private House aliveHouse() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        lenient().when(house.getId()).thenReturn(1L); // 봇 해체 판정이 house.getId() 로 사람 수를 센다
        return house;
    }

    private HouseMember activeMember(boolean owner) {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.isOwner()).thenReturn(owner);
        return member;
    }

    @Test
    void 일반_구성원이_탈퇴하면_LEFT_전환과_함께_구성원_수가_감소한다() {
        House house = aliveHouse();
        HouseMember me = activeMember(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(me));
        when(houseMemberRepository.countActiveHumans(1L, HouseMemberStatus.ACTIVE)).thenReturn(3L, 2L);

        houseMemberCommandService.leave(7L, 1L);

        verify(me).leave();
        verify(house).decreaseMemberCount();
        verify(house, never()).softDelete();
    }

    @Test
    void 소유자는_다른_구성원이_있으면_양도_전_탈퇴할_수_없다() {
        House house = aliveHouse();
        HouseMember owner = activeMember(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));
        when(houseMemberRepository.countActiveHumans(1L, HouseMemberStatus.ACTIVE)).thenReturn(3L, 2L);

        assertThatThrownBy(() -> houseMemberCommandService.leave(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_OWNER_MUST_TRANSFER));
        verify(owner, never()).leave();
        verify(house, never()).decreaseMemberCount();
    }

    @Test
    void 마지막_구성원이_탈퇴하면_집이_soft_delete_된다() {
        House house = aliveHouse();
        HouseMember lastOwner = activeMember(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(lastOwner));
        when(houseMemberRepository.countActiveHumans(1L, HouseMemberStatus.ACTIVE)).thenReturn(1L, 0L);

        houseMemberCommandService.leave(7L, 1L);

        verify(lastOwner).leave();
        verify(house).decreaseMemberCount();
        verify(house).softDelete();
    }

    @Test
    void 마지막_1인은_일반_구성원이어도_집이_정리된다() {
        // 양도 직후 소유자가 나가고 남은 MEMBER 혼자인 경우 등
        House house = aliveHouse();
        HouseMember lastMember = activeMember(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(lastMember));
        when(houseMemberRepository.countActiveHumans(1L, HouseMemberStatus.ACTIVE)).thenReturn(1L, 0L);

        houseMemberCommandService.leave(7L, 1L);

        verify(lastMember).leave();
        verify(house).softDelete();
    }

    @Test
    void 비구성원의_탈퇴는_403() {
        House house = aliveHouse();
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.leave(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 이미_탈퇴한_구성원의_재탈퇴는_403() {
        House house = aliveHouse();
        HouseMember left = mock(HouseMember.class);
        when(left.isActive()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(left));

        assertThatThrownBy(() -> houseMemberCommandService.leave(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
        verify(left, never()).leave();
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.leave(7L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House deleted = mock(House.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> houseMemberCommandService.leave(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
