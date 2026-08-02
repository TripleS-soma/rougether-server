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

    // 루틴 → 단체미션 연동: 미션 존재(삭제 제외) + 소속 집 미삭제 + 그 집의 ACTIVE 구성원인지 확인
    public void validateMissionLink(Long userId, Long houseMissionId) {
        HouseMission mission = houseMissionRepository.findById(houseMissionId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        Long houseId = mission.getHouse().getId();
        // 집이 정리(삭제)됐는데 미션 row 가 남은 경우 - 삭제된 집의 미션에 새 연동이 걸리면 안 된다.
        houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        requireActiveMember(userId, houseId);
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
