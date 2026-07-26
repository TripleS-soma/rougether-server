package com.triples.rougether.domain.invite.repository;

import com.triples.rougether.domain.invite.entity.UserInviteCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserInviteCodeRepository extends JpaRepository<UserInviteCode, Long> {

    Optional<UserInviteCode> findByUserId(Long userId);

    // lazy 발급의 "락 이후 재조회" 전용 — 일반 조회는 REPEATABLE READ 스냅샷을 읽어
    // 동시 발급의 선행 커밋을 못 본다(uq_user_invite_codes_user 위반 → 500).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from UserInviteCode c where c.user.id = :userId")
    Optional<UserInviteCode> findForUpdateByUserId(@Param("userId") Long userId);

    // 코드 입력(redeem) 경로 — 코드로 초대자를 찾는다.
    Optional<UserInviteCode> findByCode(String code);

    boolean existsByCode(String code);
}
