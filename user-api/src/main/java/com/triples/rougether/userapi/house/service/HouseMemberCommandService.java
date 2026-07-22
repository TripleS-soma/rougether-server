package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.TransferOwnershipResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 구성원 관리 명령(양도·탈퇴·강퇴). 소유권 양도는 role 전환 2건 + owner_user_id 갱신을 단일 트랜잭션으로.
@Service
@RequiredArgsConstructor
public class HouseMemberCommandService {

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final NotificationService notificationService;

    @Transactional
    public TransferOwnershipResponse transferOwnership(Long userId, Long houseId, Long targetMembershipId) {
        House house = houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));

        HouseMember requester = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .filter(HouseMember::isOwner)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER));

        HouseMember target = houseMemberRepository.findById(targetMembershipId)
                .filter(HouseMember::isActive)
                .filter(found -> found.getHouse().getId().equals(houseId))
                .filter(found -> !found.getId().equals(requester.getId()))
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_TRANSFER_TARGET_INVALID));

        requester.demoteToMember();
        target.promoteToOwner();
        house.changeOwner(target.getUser());

        return new TransferOwnershipResponse(houseId, target.getId(), target.getUser().getId());
    }

    // 집 탈퇴. 소유자는 다른 활성 구성원이 있으면 양도 선행, 마지막 1인이면 탈퇴와 함께 집을 정리한다.
    // 참여(count 증가)와 대칭으로 house 행 락 아래에서 count 를 감소시킨다.
    @Transactional
    public void leave(Long userId, Long houseId) {
        House house = houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));

        HouseMember me = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));

        long activeCount = houseMemberRepository.countByHouseIdAndStatus(houseId, HouseMemberStatus.ACTIVE);
        if (me.isOwner() && activeCount > 1) {
            throw new BusinessException(HouseErrorCode.HOUSE_OWNER_MUST_TRANSFER);
        }

        // 수신 대상은 탈퇴자를 뺀 남은 활성 멤버 - 상태 전환 전에 확정해야 본인이 섞이지 않음.
        List<HouseMember> recipients = houseMemberRepository
                .findByHouseIdAndStatusWithUser(houseId, HouseMemberStatus.ACTIVE).stream()
                .filter(member -> !member.getId().equals(me.getId()))
                .toList();

        me.leave();
        house.decreaseMemberCount();
        if (activeCount == 1) {
            // 마지막 구성원 - 빈 집이 탐색에 남지 않게 정리.
            house.softDelete();
        }
        notifyMemberLeft(recipients, me);
    }

    // 퇴거 알림 - 탈퇴와 같은 트랜잭션에서 동기 저장(응원 #174 패턴). 강퇴(kick)는 범위 밖이라 붙이지 않음.
    private void notifyMemberLeft(List<HouseMember> recipients, HouseMember left) {
        if (recipients.isEmpty()) {
            return;
        }
        var content = NotificationMessages.houseMemberLeft(left.getUser().getNickname());
        recipients.forEach(recipient -> notificationService.send(
                recipient.getUser().getId(), content, left.getId()));
    }

    // 강퇴 - 소유자 전용. KICKED 전환으로 재가입까지 차단한다. 알림 발송은 알림 도메인 의존(후속).
    @Transactional
    public void kick(Long userId, Long houseId, Long targetMembershipId) {
        House house = houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));

        HouseMember requester = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .filter(HouseMember::isOwner)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER));
        if (requester.getId().equals(targetMembershipId)) {
            throw new BusinessException(HouseErrorCode.HOUSE_KICK_SELF);
        }

        HouseMember target = houseMemberRepository.findById(targetMembershipId)
                .filter(HouseMember::isActive)
                .filter(found -> found.getHouse().getId().equals(houseId))
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));

        target.kick();
        house.decreaseMemberCount();
    }
}
