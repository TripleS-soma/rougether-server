package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseResult;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseSelection;
import com.triples.rougether.domain.onboarding.repository.OnboardingHouseSelectionRepository;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingHouseResponse;
import com.triples.rougether.userapi.onboarding.error.OnboardingHouseErrorCode;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingHouseTransactionService {
    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseJoinRequestRepository houseJoinRequestRepository;
    private final UserRepository userRepository;
    private final OnboardingHouseSelectionRepository selectionRepository;
    private final HouseCommandService houseCommandService;
    private final HouseJoinService houseJoinService;
    private final HouseGoalRepository houseGoalRepository;
    private final UserGoalRepository userGoalRepository;

    // 후보마다 독립 트랜잭션을 사용하여 실패한 후보의 락을 다음 후보까지 끌고 가지 않음.
    // 기존 즉시가입과 같은 집 → 사용자 순서로 잠그며, 사용자 락 아래 결과를 재조회해 중복 가입을 막음.
    public OnboardingHouseResponse tryJoin(Long userId, Long houseId) {
        House house = houseRepository.findWithLockById(houseId).orElse(null);
        requireUser(userId);
        OnboardingHouseResponse previous = previous(userId, OnboardingHouseChoice.AUTO_JOIN);
        if (previous != null) {
            return previous;
        }
        if (house == null || house.isDeleted() || !house.isPublic() || !house.isOnboardingAutoJoinEnabled()
                || house.getOwner().getId().equals(userId) || house.getOwner().isBot()
                || house.getOwner().isDeleted()
                || houseMemberRepository.findWithLockByHouseIdAndUserId(houseId, userId).isPresent()
                || houseJoinRequestRepository.findWithLockByHouseIdAndUserId(houseId, userId).isPresent()) {
            return null;
        }
        long humans = houseMemberRepository.countActiveHumans(houseId, HouseMemberStatus.ACTIVE);
        if (humans == 0 || (house.getMaxMembers() != null && humans >= house.getMaxMembers())) {
            return null;
        }
        var joined = houseJoinService.joinForOnboarding(house, userId);
        HouseMember member = houseMemberRepository.findById(joined.membershipId()).orElseThrow();
        return save(userId, OnboardingHouseChoice.AUTO_JOIN, OnboardingHouseResult.JOINED, member);
    }

    public OnboardingHouseResponse startPersonal(Long userId, OnboardingHouseChoice choice, Long houseId) {
        House house = houseId == null ? null : houseRepository.findWithLockById(houseId).orElse(null);
        User user = requireUser(userId);
        OnboardingHouseResponse previous = previous(userId, choice);
        if (previous != null) {
            return previous;
        }
        HouseMember member;
        if (houseId != null) {
            member = houseMemberRepository.findWithLockByHouseIdAndUserId(houseId, userId).orElse(null);
            // 후보 조회 뒤 양도·탈퇴·공개 전환이 일어났으면 새 스냅샷에서 다시 선택함.
            if (house == null || house.isDeleted() || house.isPublic() || member == null
                    || !member.isActive() || !member.isOwner() || !house.getOwner().getId().equals(userId)
                    || houseMemberRepository.countActiveHumans(houseId, HouseMemberStatus.ACTIVE) != 1) {
                return null;
            }
        } else {
            house = houseCommandService.createStarterHouse(user);
            if (user.getNickname() != null) {
                house.updateSettings(user.getNickname() + "의 집", null, null, null, null);
            }
            House created = house;
            houseGoalRepository.saveAll(userGoalRepository.findByUserIdWithGoalOrderBySortOrder(userId).stream()
                    .sorted(Comparator.comparing(UserGoal::isPrimary).reversed()
                            .thenComparingInt(goal -> goal.getGoal().getSortOrder()))
                    .limit(3).map(goal -> HouseGoal.create(created, goal.getGoal())).toList());
            member = houseMemberRepository.findByHouseIdAndUserId(house.getId(), userId).orElseThrow();
        }
        boolean noMatch = choice == OnboardingHouseChoice.AUTO_JOIN;
        house.updateSettings(null, null, null, null, noMatch);
        house.changeOnboardingAutoJoinEnabled(noMatch);
        return save(userId, choice, noMatch ? OnboardingHouseResult.NO_MATCH : OnboardingHouseResult.PERSONAL, member);
    }

    private User requireUser(Long userId) {
        return userRepository.findByIdForUpdate(userId).filter(user -> !user.isDeleted() && !user.isBot())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
    }

    private OnboardingHouseResponse previous(Long userId, OnboardingHouseChoice choice) {
        return selectionRepository.findWithLockByUserId(userId).map(selection -> {
            if (selection.getChoice() != choice) {
                throw new BusinessException(OnboardingHouseErrorCode.ONBOARDING_HOUSE_ALREADY_SELECTED);
            }
            return OnboardingHouseResponse.of(selection);
        }).orElse(null);
    }

    private OnboardingHouseResponse save(Long userId, OnboardingHouseChoice choice,
                                         OnboardingHouseResult result, HouseMember member) {
        return OnboardingHouseResponse.of(selectionRepository.save(
                OnboardingHouseSelection.create(userId, choice, result, member)));
    }
}
