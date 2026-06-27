package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 재사용 감지 시 해당 user의 살아있는 refresh 일괄 폐기용.
    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(Long userId);

    // 회전 시 원자적 1회성 폐기. 동시 요청이 같은 토큰을 회전하면 한쪽만 영향행 1, 진 쪽은 0 → 재사용으로 처리함.
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.id = :id and r.revokedAt is null")
    int revokeIfActive(@Param("id") Long id, @Param("now") Instant now);
}
