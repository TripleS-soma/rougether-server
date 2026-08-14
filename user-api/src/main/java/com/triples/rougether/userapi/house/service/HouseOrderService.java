package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseOrderService {

    private final HouseMemberRepository houseMemberRepository;

    public HouseOrderService(HouseMemberRepository houseMemberRepository) {
        this.houseMemberRepository = houseMemberRepository;
    }

    @Transactional
    public void reorder(Long userId, List<Long> houseIds) {
        List<HouseMember> memberships = houseMemberRepository
                .findAllWithLockByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE);
        Map<Long, HouseMember> membershipByHouseId = new HashMap<>();
        memberships.forEach(member -> membershipByHouseId.put(member.getHouse().getId(), member));
        Set<Long> requestedHouseIds = new HashSet<>(houseIds);
        if (houseIds.size() != memberships.size()
                || requestedHouseIds.size() != houseIds.size()
                || !requestedHouseIds.equals(membershipByHouseId.keySet())) {
            throw new BusinessException(HouseErrorCode.HOUSE_ORDER_INVALID);
        }

        for (int order = 0; order < houseIds.size(); order++) {
            membershipByHouseId.get(houseIds.get(order)).changeDisplayOrder(order);
        }
    }
}
