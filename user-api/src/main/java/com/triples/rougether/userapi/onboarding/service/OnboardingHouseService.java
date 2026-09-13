package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.onboarding.entity.OnboardingHouseChoice;
import com.triples.rougether.domain.onboarding.repository.OnboardingHouseSelectionRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.onboarding.dto.OnboardingHouseResponse;
import com.triples.rougether.userapi.onboarding.error.OnboardingHouseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingHouseService {
    private final HouseRepository houseRepository;
    private final UserRepository userRepository;
    private final OnboardingHouseSelectionRepository selectionRepository;
    private final OnboardingHouseTransactionService transactions;

    @Transactional(readOnly = true)
    public OnboardingHouseResponse get(Long userId) {
        userRepository.findById(userId).filter(user -> !user.isDeleted() && !user.isBot())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        return selectionRepository.findById(userId).map(OnboardingHouseResponse::of)
                .orElseGet(OnboardingHouseResponse::pending);
    }

    // 조회와 후보별 쓰기 트랜잭션을 분리함. 후보가 만석·비공개로 바뀌면 다음 후보를 시도함.
    public OnboardingHouseResponse select(Long userId, OnboardingHouseChoice choice) {
        OnboardingHouseResponse previous = get(userId);
        if (previous.completed()) {
            if (previous.choice() != choice) {
                throw new BusinessException(OnboardingHouseErrorCode.ONBOARDING_HOUSE_ALREADY_SELECTED);
            }
            return previous;
        }
        if (choice == OnboardingHouseChoice.AUTO_JOIN) {
            long afterId = 0;
            while (true) {
                var candidates = houseRepository.findOnboardingCandidates(userId, afterId, PageRequest.of(0, 20));
                if (candidates.isEmpty()) {
                    break;
                }
                for (Long houseId : candidates) {
                    OnboardingHouseResponse result = transactions.tryJoin(userId, houseId);
                    if (result != null) {
                        return result;
                    }
                    afterId = houseId;
                }
            }
        }
        while (true) {
            var houses = houseRepository.findPersonalOnboardingCandidates(userId, PageRequest.of(0, 1));
            OnboardingHouseResponse result = transactions.startPersonal(
                    userId, choice, houses.isEmpty() ? null : houses.getFirst());
            if (result != null) {
                return result;
            }
        }
    }
}
