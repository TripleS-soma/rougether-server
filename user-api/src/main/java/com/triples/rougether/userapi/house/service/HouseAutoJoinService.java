package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseAutoJoinResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HouseAutoJoinService {
    private final HouseRepository houseRepository;
    private final HouseMemberRepository memberRepository;

    public HouseAutoJoinResponse get(Long userId, Long houseId) {
        House house = houseRepository.findById(houseId).filter(h -> !h.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        requireOwner(house, userId);
        return new HouseAutoJoinResponse(houseId, house.isOnboardingAutoJoinEnabled());
    }

    @Transactional
    public HouseAutoJoinResponse update(Long userId, Long houseId, boolean enabled) {
        House house = houseRepository.findWithLockById(houseId).filter(h -> !h.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        requireOwner(house, userId);
        house.changeOnboardingAutoJoinEnabled(enabled);
        return new HouseAutoJoinResponse(houseId, enabled);
    }

    private void requireOwner(House house, Long userId) {
        if (!house.getOwner().getId().equals(userId)
                || memberRepository.findByHouseIdAndUserId(house.getId(), userId)
                        .filter(HouseMember::isActive).filter(HouseMember::isOwner).isEmpty()) {
            throw new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER);
        }
    }
}
