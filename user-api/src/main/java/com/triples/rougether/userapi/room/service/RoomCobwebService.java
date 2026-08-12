package com.triples.rougether.userapi.room.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.RoomCobweb;
import com.triples.rougether.domain.room.repository.RoomCobwebRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.room.dto.RoomCobwebCleanResponse;
import com.triples.rougether.userapi.room.error.RoomErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoomCobwebService {

    private static final int CLEAN_REWARD_COINS = 3;

    private final RoomCobwebRepository roomCobwebRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final UserWalletRepository userWalletRepository;
    private final UserRepository userRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final NotificationService notificationService;

    @Transactional
    public RoomCobwebCleanResponse cleanMyRoom(Long userId) {
        return clean(userId, userId, null);
    }

    @Transactional
    public RoomCobwebCleanResponse cleanHouseMemberRoom(Long userId, Long houseId, Long membershipId) {
        HouseMember requester = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
        HouseMember target = houseMemberRepository.findById(membershipId)
                .filter(member -> member.getHouse().getId().equals(houseId))
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
        String cleanerNickname = requester.getUser().getNickname();
        return clean(userId, target.getUser().getId(),
                cleanerNickname == null || cleanerNickname.isBlank() ? "집 친구" : cleanerNickname);
    }

    private RoomCobwebCleanResponse clean(Long cleanerUserId, Long roomUserId, String cleanerNickname) {
        RoomCobweb cobweb = roomCobwebRepository.findActiveForUpdate(roomUserId)
                .orElseThrow(() -> new BusinessException(RoomErrorCode.COBWEB_NOT_ACTIVE));
        Instant now = Instant.now();
        cobweb.clean(userRepository.getReferenceById(cleanerUserId), now);

        UserWallet wallet = userWalletRepository
                .findWithLockByUserIdAndCurrencyType(cleanerUserId, CurrencyType.COIN)
                .orElseThrow(() -> new BusinessException(RoomErrorCode.COBWEB_REWARD_WALLET_NOT_FOUND));
        wallet.add(CLEAN_REWARD_COINS);
        walletHistoryRecorder.record(wallet, CLEAN_REWARD_COINS, WalletHistoryReason.COBWEB_CLEAN,
                WalletHistory.SOURCE_ROOM_COBWEB, roomUserId);

        if (!cleanerUserId.equals(roomUserId)) {
            notificationService.send(
                    roomUserId,
                    NotificationMessages.roomCobwebCleaned(cleanerNickname),
                    roomUserId);
        }
        return new RoomCobwebCleanResponse(
                roomUserId, now, CurrencyType.COIN, CLEAN_REWARD_COINS, wallet.getBalance());
    }
}
