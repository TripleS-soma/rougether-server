package com.triples.rougether.userapi.house.support;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import org.springframework.stereotype.Component;

// 루틴·카테고리의 집/단체미션 연동 요청 검증. 연동 대상 집의 ACTIVE 구성원만 연동을 지정할 수 있다.
// 연동 값 자체는 FK 없는 식별자 보관이라, 저장 이후의 미션·집 삭제까지 여기서 막지는 않는다.
@Component
public class HouseLinkValidator {

    private final HouseRepository houseRepository;
    private final HouseMissionRepository houseMissionRepository;
    private final HouseMemberRepository houseMemberRepository;

    public HouseLinkValidator(HouseRepository houseRepository,
                              HouseMissionRepository houseMissionRepository,
                              HouseMemberRepository houseMemberRepository) {
        this.houseRepository = houseRepository;
        this.houseMissionRepository = houseMissionRepository;
        this.houseMemberRepository = houseMemberRepository;
    }

    // 루틴 → 단체미션 연동: 미션 존재(삭제 제외) + 그 미션이 속한 집의 ACTIVE 구성원인지 확인
    public void validateMissionLink(Long userId, Long houseMissionId) {
        HouseMission mission = houseMissionRepository.findById(houseMissionId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        requireActiveMember(userId, mission.getHouse().getId());
    }

    // 카테고리 → 집 연동: 집 존재(삭제 제외) + 그 집의 ACTIVE 구성원인지 확인
    public void validateHouseLink(Long userId, Long houseId) {
        houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        requireActiveMember(userId, houseId);
    }

    private void requireActiveMember(Long userId, Long houseId) {
        houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
    }
}
