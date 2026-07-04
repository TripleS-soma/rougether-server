package com.triples.rougether.userapi.member.service;

import com.triples.rougether.userapi.member.dto.MeResponse;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.onboarding.service.OnboardingQueryService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final OnboardingQueryService onboardingQueryService;

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        OffsetDateTime lastLoginAt = user.getLastLoginAt() == null
                ? null
                : user.getLastLoginAt().atZone(KST).toOffsetDateTime();
        return new MeResponse(user.getId(), user.getNickname(), lastLoginAt,
                onboardingQueryService.getSummary(userId));
    }
}
