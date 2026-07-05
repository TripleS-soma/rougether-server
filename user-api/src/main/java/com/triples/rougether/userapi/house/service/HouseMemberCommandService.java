package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.TransferOwnershipResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 구성원 관리 명령(양도·탈퇴·강퇴). 소유권 양도는 role 전환 2건 + owner_user_id 갱신을 단일 트랜잭션으로.
@Service
public class HouseMemberCommandService {

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;

    public HouseMemberCommandService(HouseRepository houseRepository,
                                     HouseMemberRepository houseMemberRepository) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
    }

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

        me.leave();
        house.decreaseMemberCount();
        if (activeCount == 1) {
            // 마지막 구성원 - 빈 집이 탐색에 남지 않게 정리.
            house.softDelete();
        }
    }
}
