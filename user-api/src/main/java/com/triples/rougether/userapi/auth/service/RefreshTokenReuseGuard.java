package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 재사용 감지 시 폐기는 거부(예외)와 무관하게 반드시 커밋돼야 함 → REQUIRES_NEW 로 분리함.
// 같은 트랜잭션에서 폐기 후 예외를 던지면 롤백돼 방어가 무력화되기 때문.
@Component
@RequiredArgsConstructor
public class RefreshTokenReuseGuard {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllActive(Long userId, Instant now) {
        for (RefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)) {
            token.revoke(now);
        }
    }
}
