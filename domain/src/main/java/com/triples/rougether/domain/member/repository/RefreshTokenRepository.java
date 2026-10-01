package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // refresh 진입점용 잠금 조회(SELECT ... FOR UPDATE). 같은 토큰을 든 동시 요청을 줄 세우고,
    // 잠금 읽기라 앞선 요청이 커밋한 최신 폐기 상태(사유·시각)를 봄. user 는 잠그지 않도록 fetch join 하지 않음.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 회원탈퇴 시 해당 user의 살아있는 refresh 일괄 폐기용.
    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(Long userId);

    // 회전 시 원자적 1회성 폐기. family_id 도 함께 기록함(레거시 null 토큰은 이때 새 family 를 받음).
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now, r.revokeReason = :reason, r.familyId = :familyId "
            + "where r.id = :id and r.revokedAt is null")
    int revokeIfActive(@Param("id") Long id, @Param("now") Instant now,
                       @Param("reason") RefreshTokenRevokeReason reason, @Param("familyId") String familyId);

    // 한 family(기기 1대)의 살아있는 토큰만 폐기함. 다른 기기의 family 는 건드리지 않음.
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now, r.revokeReason = :reason "
            + "where r.familyId = :familyId and r.revokedAt is null")
    int revokeActiveInFamily(@Param("familyId") String familyId, @Param("now") Instant now,
                             @Param("reason") RefreshTokenRevokeReason reason);

    // 레거시(family 없음) 토큰 재사용 시 폴백: 회원의 살아있는 토큰 전부 폐기.
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now, r.revokeReason = :reason "
            + "where r.user.id = :userId and r.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now,
                                @Param("reason") RefreshTokenRevokeReason reason);
}
