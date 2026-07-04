package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 초대코드 참여(즉시가입) + 참여 전 미리보기.
// 정원 검사와 구성원 수 증가는 house 행 락 아래 같은 트랜잭션에서 처리해 동시 참여 초과를 막는다.
@Service
public class HouseJoinService {

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final UserRepository userRepository;

    public HouseJoinService(HouseRepository houseRepository,
                            HouseMemberRepository houseMemberRepository,
                            UserRepository userRepository) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public HouseJoinResponse joinByCode(Long userId, String inviteCode) {
        House house = houseRepository.findWithLockByInviteCode(inviteCode)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));
        if (house.isInviteExpired()) {
            throw new BusinessException(HouseErrorCode.INVITE_CODE_EXPIRED);
        }

        HouseMember existing = houseMemberRepository.findByHouseIdAndUserId(house.getId(), userId)
                .orElse(null);
        if (existing != null && existing.isActive()) {
            throw new BusinessException(HouseErrorCode.HOUSE_ALREADY_MEMBER);
        }
        if (house.isFull()) {
            throw new BusinessException(HouseErrorCode.HOUSE_FULL);
        }

        HouseMember member;
        if (existing != null) {
            // LEFT 이력 재가입 - uq_house_member 제약상 새 row 대신 재활성화.
            existing.reactivate();
            member = existing;
        } else {
            member = houseMemberRepository.save(
                    HouseMember.create(house, userRepository.getReferenceById(userId), HouseMemberRole.MEMBER));
        }
        house.increaseMemberCount();

        return new HouseJoinResponse(member.getId(), house.getId(), member.getStatus());
    }

    @Transactional(readOnly = true)
    public HousePreviewResponse preview(String inviteCode) {
        House house = houseRepository.findByInviteCode(inviteCode)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.INVITE_CODE_INVALID));
        return HousePreviewResponse.of(house);
    }
}
