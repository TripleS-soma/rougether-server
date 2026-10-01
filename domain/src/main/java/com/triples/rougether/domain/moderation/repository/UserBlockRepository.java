package com.triples.rougether.domain.moderation.repository;

import com.triples.rougether.domain.moderation.entity.UserBlock;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    boolean existsByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);

    // 해제는 행이 없어도 성공(멱등)
    @Modifying
    @Query("delete from UserBlock b where b.blockerUserId = :blocker and b.blockedUserId = :blocked")
    int deletePair(@Param("blocker") Long blockerUserId, @Param("blocked") Long blockedUserId);

    // 내 차단 목록: 최근 차단순, 차단 row id 커서(id < before)
    @Query("select b from UserBlock b where b.blockerUserId = :blocker "
            + "and (:before is null or b.id < :before) order by b.id desc")
    List<UserBlock> findPage(@Param("blocker") Long blockerUserId, @Param("before") Long before, Pageable page);

    // 회원탈퇴: 탈퇴자가 한 차단·탈퇴자를 대상으로 한 차단을 모두 지움
    @Modifying
    @Query("delete from UserBlock b where b.blockerUserId = :userId or b.blockedUserId = :userId")
    int deleteAllInvolving(@Param("userId") Long userId);
}
