package com.triples.rougether.userapi.room;

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
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.RoomCobweb;
import com.triples.rougether.domain.room.repository.RoomCobwebRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.room.dto.RoomCobwebCleanResponse;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.room.service.RoomCobwebService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomCobwebServiceTest {

    private static final Long CLEANER_ID = 7L;
    private static final Long OWNER_ID = 8L;
    private static final Long HOUSE_ID = 1L;
    private static final Long MEMBERSHIP_ID = 12L;

    @Mock private RoomCobwebRepository roomCobwebRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private UserWalletRepository userWalletRepository;
    @Mock private UserRepository userRepository;
    @Mock private WalletHistoryRecorder walletHistoryRecorder;
    @Mock private NotificationService notificationService;
    @InjectMocks private RoomCobwebService roomCobwebService;

    @Test
    void 같은_집_구성원이_활성_거미줄을_청소하면_코인3개를_받고_주인에게_알림을_보낸다() {
        HouseMember cleanerMember = member(HOUSE_ID, CLEANER_ID, "청소왕");
        HouseMember ownerMember = member(HOUSE_ID, OWNER_ID, "잠든이");
        RoomCobweb cobweb = activeCobweb(OWNER_ID);
        UserWallet wallet = coinWallet(CLEANER_ID, 10);
        User cleanerUser = cleanerMember.getUser();
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, CLEANER_ID))
                .thenReturn(Optional.of(cleanerMember));
        when(houseMemberRepository.findById(MEMBERSHIP_ID)).thenReturn(Optional.of(ownerMember));
        when(roomCobwebRepository.findActiveForUpdate(OWNER_ID)).thenReturn(Optional.of(cobweb));
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(CLEANER_ID, CurrencyType.COIN))
                .thenReturn(Optional.of(wallet));
        when(userRepository.getReferenceById(CLEANER_ID)).thenReturn(cleanerUser);

        RoomCobwebCleanResponse response = roomCobwebService.cleanHouseMemberRoom(
                CLEANER_ID, HOUSE_ID, MEMBERSHIP_ID);

        assertThat(response.rewardAmount()).isEqualTo(3);
        assertThat(response.balance()).isEqualTo(13);
        verify(cobweb).clean(org.mockito.ArgumentMatchers.eq(cleanerUser),
                org.mockito.ArgumentMatchers.any(Instant.class));
        verify(walletHistoryRecorder).record(wallet, 3, com.triples.rougether.domain.shared.WalletHistoryReason.COBWEB_CLEAN,
                "ROOM_COBWEB", OWNER_ID);
        verify(notificationService).send(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(OWNER_ID));
    }

    @Test
    void 내_방_거미줄도_직접_청소할_수_있지만_자기자신에게_알림은_보내지_않는다() {
        RoomCobweb cobweb = activeCobweb(CLEANER_ID);
        UserWallet wallet = coinWallet(CLEANER_ID, 0);
        User cleanerUser = wallet.getUser();
        when(roomCobwebRepository.findActiveForUpdate(CLEANER_ID)).thenReturn(Optional.of(cobweb));
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(CLEANER_ID, CurrencyType.COIN))
                .thenReturn(Optional.of(wallet));
        when(userRepository.getReferenceById(CLEANER_ID)).thenReturn(cleanerUser);

        RoomCobwebCleanResponse response = roomCobwebService.cleanMyRoom(CLEANER_ID);

        assertThat(response.balance()).isEqualTo(3);
        verify(notificationService, never()).send(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 코인_지갑이_없으면_보상_실패_에러를_반환하고_원장과_알림을_남기지_않는다() {
        RoomCobweb cobweb = activeCobweb(CLEANER_ID);
        User cleanerUser = mock(User.class);
        when(roomCobwebRepository.findActiveForUpdate(CLEANER_ID)).thenReturn(Optional.of(cobweb));
        when(userRepository.getReferenceById(CLEANER_ID)).thenReturn(cleanerUser);
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(CLEANER_ID, CurrencyType.COIN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomCobwebService.cleanMyRoom(CLEANER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(RoomErrorCode.COBWEB_REWARD_WALLET_NOT_FOUND));
        verify(walletHistoryRecorder, never()).record(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(notificationService, never()).send(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 이미_청소된_거미줄은_다시_보상하지_않는다() {
        when(roomCobwebRepository.findActiveForUpdate(CLEANER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomCobwebService.cleanMyRoom(CLEANER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(RoomErrorCode.COBWEB_NOT_ACTIVE));
        verify(userWalletRepository, never()).findWithLockByUserIdAndCurrencyType(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 다른_집_사용자는_청소할_수_없다() {
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, CLEANER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomCobwebService.cleanHouseMemberRoom(CLEANER_ID, HOUSE_ID, MEMBERSHIP_ID))
                .isInstanceOf(BusinessException.class);
        verify(roomCobwebRepository, never()).findActiveForUpdate(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 온보딩전_청소자는_알림에서_집친구로_표시한다() {
        HouseMember cleanerMember = member(HOUSE_ID, CLEANER_ID, null);
        HouseMember ownerMember = member(HOUSE_ID, OWNER_ID, "잠든이");
        UserWallet wallet = coinWallet(CLEANER_ID, 0);
        User cleanerUser = cleanerMember.getUser();
        when(houseMemberRepository.findByHouseIdAndUserId(HOUSE_ID, CLEANER_ID))
                .thenReturn(Optional.of(cleanerMember));
        when(houseMemberRepository.findById(MEMBERSHIP_ID)).thenReturn(Optional.of(ownerMember));
        RoomCobweb cobweb = activeCobweb(OWNER_ID);
        when(roomCobwebRepository.findActiveForUpdate(OWNER_ID)).thenReturn(Optional.of(cobweb));
        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(CLEANER_ID, CurrencyType.COIN))
                .thenReturn(Optional.of(wallet));
        when(userRepository.getReferenceById(CLEANER_ID)).thenReturn(cleanerUser);

        roomCobwebService.cleanHouseMemberRoom(CLEANER_ID, HOUSE_ID, MEMBERSHIP_ID);

        verify(notificationService).send(org.mockito.ArgumentMatchers.eq(OWNER_ID),
                org.mockito.ArgumentMatchers.argThat(content -> content.body().startsWith("집 친구님이")),
                org.mockito.ArgumentMatchers.eq(OWNER_ID));
    }

    private HouseMember member(Long houseId, Long userId, String nickname) {
        House house = mock(House.class);
        lenient().when(house.getId()).thenReturn(houseId);
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(user.getNickname()).thenReturn(nickname);
        HouseMember member = mock(HouseMember.class);
        lenient().when(member.isActive()).thenReturn(true);
        lenient().when(member.getHouse()).thenReturn(house);
        lenient().when(member.getUser()).thenReturn(user);
        return member;
    }

    private RoomCobweb activeCobweb(Long ownerUserId) {
        RoomCobweb cobweb = mock(RoomCobweb.class);
        lenient().when(cobweb.getRoomUserId()).thenReturn(ownerUserId);
        return cobweb;
    }

    private UserWallet coinWallet(Long userId, int balance) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        UserWallet wallet = mock(UserWallet.class);
        int[] current = {balance};
        lenient().when(wallet.getUser()).thenReturn(user);
        lenient().when(wallet.getCurrencyType()).thenReturn(CurrencyType.COIN);
        lenient().when(wallet.getBalance()).thenAnswer(invocation -> current[0]);
        org.mockito.Mockito.doAnswer(invocation -> {
            current[0] += invocation.getArgument(0, Integer.class);
            return null;
        }).when(wallet).add(org.mockito.ArgumentMatchers.anyInt());
        return wallet;
    }
}
