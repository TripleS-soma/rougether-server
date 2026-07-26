package com.triples.rougether.domain.invite.repository;

import com.triples.rougether.domain.invite.entity.InviteReward;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteRewardRepository extends JpaRepository<InviteReward, Long> {

    // 판정용 조회는 반드시 locking read 로 한다 — REPEATABLE READ 에선 일반 조회가 트랜잭션 첫 읽기 시점의
    // 스냅샷을 보므로, 사용자 행 락을 잡은 뒤 읽어도 선행 커밋된 지급 이력을 못 본다(한도 우회·중복 판정 실패).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InviteReward r where r.invitee.id = :inviteeUserId")
    List<InviteReward> findForUpdateByInviteeId(@Param("inviteeUserId") Long inviteeUserId);

    // 초대자 한도 판정. 실제 지급된 건(inviter_reward_amount > 0)만 센다 — 한도 초과로 0원 지급된 행은
    // 중복 사용 판정에만 쓰이고 한도 소진에는 포함하지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InviteReward r where r.inviter.id = :inviterUserId and r.inviterRewardAmount > 0")
    List<InviteReward> findRewardedForUpdateByInviterId(@Param("inviterUserId") Long inviterUserId);

    // 조회 전용(내 초대코드 화면) — 판정에 쓰지 않으므로 잠금 없이 센다.
    long countByInviterIdAndInviterRewardAmountGreaterThan(Long inviterUserId, int amount);
}
